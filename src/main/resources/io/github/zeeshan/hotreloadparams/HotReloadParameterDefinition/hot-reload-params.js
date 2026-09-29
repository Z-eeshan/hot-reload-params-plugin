/**
 * hot-reload-params.js
 *
 * Hot-reloads the parameters list on the "Build with Parameters" page.
 * When the trigger parameter (e.g. RELEASE_BRANCH) changes, the plugin:
 *   1. Asks the server for the parameter contract of the matching Git branch
 *      (the server reads the repository configuration from the job itself)
 *   2. Hides (and disables) parameters that don't exist on the target branch
 *   3. Updates / creates parameters that do exist on the target branch
 *   4. Reorders rows so DOM order matches the branch's parameter order
 *   5. Points the "Build" form at the plugin's submission endpoint, which
 *      validates every submitted parameter against the same branch contract.
 */
(function () {
  "use strict";

  var DEBOUNCE_MS = 600;

  var root = null;
  var config = {};
  var debounceTimer = null;
  // Rows this script created for parameters that only exist on the branch.
  var dynamicRows = [];
  // Trigger value of the most recent request. Re-applying the same value would
  // wipe out edits the user made in the meantime, so we never do that.
  var lastRequestedValue = null;
  // Monotonic counter so a slow, superseded response can't overwrite a newer one.
  var requestSeq = 0;

  // ── Initialization ───────────────────────────────────────────────────────

  function init() {
    root = document.getElementById("hot-reload-params-root");
    if (!root) {
      return;
    }

    config = {
      triggerParamName: root.dataset.triggerParamName || "RELEASE_BRANCH",
      defaultBranch: root.dataset.defaultBranch || "master",
      endpointUrl: root.dataset.endpointUrl || "",
      jobFullName: root.dataset.jobFullName || "",
    };

    attachTriggerListener();
    repositionBanner();
    redirectBuildForm();

    var triggerInput = findParamValueInput(config.triggerParamName);
    var initialValue = triggerInput && triggerInput.value ? triggerInput.value.trim() : "";
    fetchAndApply(initialValue || config.defaultBranch);
  }

  // ── Locate Parameter Elements ────────────────────────────────────────────

  /**
   * Find the container (form item) for a Jenkins parameter by its name.
   * Returns { container, valueInput } or null.
   */
  function findParamRow(paramName) {
    var nameInputs = document.querySelectorAll('input[name="name"]');
    for (var i = 0; i < nameInputs.length; i++) {
      if (nameInputs[i].value !== paramName) {
        continue;
      }
      var container = closestParamContainer(nameInputs[i]);
      if (!container) {
        continue;
      }
      var valueEl =
        container.querySelector('input[name="value"]') ||
        container.querySelector('select[name="value"]') ||
        container.querySelector('textarea[name="value"]');
      return { container: container, valueInput: valueEl };
    }
    return null;
  }

  /**
   * The outer container that wraps BOTH the label/description AND the
   * <div name="parameter"> inner block: <div class="jenkins-form-item">.
   */
  function closestParamContainer(el) {
    return el.closest(".jenkins-form-item") || el.closest('[name="parameter"]');
  }

  function findParamValueInput(paramName) {
    var row = findParamRow(paramName);
    return row ? row.valueInput : null;
  }

  /** All current parameter containers keyed by parameter name. */
  function getAllParamRows() {
    var map = {};
    var nameInputs = document.querySelectorAll('input[name="name"]');
    for (var i = 0; i < nameInputs.length; i++) {
      var container = closestParamContainer(nameInputs[i]);
      if (container) {
        map[nameInputs[i].value] = container;
      }
    }
    return map;
  }

  // ── Trigger Listener ─────────────────────────────────────────────────────

  /** Move the banner and loading indicator right after the trigger parameter row. */
  function repositionBanner() {
    var triggerRow = findParamRow(config.triggerParamName);
    if (!triggerRow) {
      return;
    }
    var banner = document.getElementById("drp-status-banner");
    var loading = document.getElementById("drp-loading");
    var container = triggerRow.container;
    var parent = container.parentNode;
    var next = container.nextSibling;
    if (loading && parent) parent.insertBefore(loading, next);
    if (banner && parent) parent.insertBefore(banner, next);
  }

  function attachTriggerListener() {
    var triggerInput = findParamValueInput(config.triggerParamName);
    if (!triggerInput) {
      console.warn("[hot-reload-params] Trigger input not found: " + config.triggerParamName);
      return;
    }

    var handler = function () {
      clearTimeout(debounceTimer);
      debounceTimer = setTimeout(function () {
        var value = triggerInput.value ? triggerInput.value.trim() : "";
        // Only reload when the trigger actually changed; focus/blur or a
        // re-selected identical value must not reset the user's edits.
        if (value && value !== lastRequestedValue) {
          fetchAndApply(value);
        }
      }, DEBOUNCE_MS);
    };

    triggerInput.addEventListener("change", handler);
    triggerInput.addEventListener("input", handler);
  }

  // ── AJAX Fetch ───────────────────────────────────────────────────────────

  function fetchAndApply(triggerValue) {
    lastRequestedValue = triggerValue;
    var seq = ++requestSeq;

    showLoading(true);
    hideBanner();

    var body = new URLSearchParams();
    body.append("job", config.jobFullName);
    body.append("triggerValue", triggerValue);

    fetch(config.endpointUrl + "/fetchParams", {
      method: "POST",
      headers: crumb.wrap({
        "Content-Type": "application/x-www-form-urlencoded",
      }),
      body: body.toString(),
    })
      .then(function (resp) {
        if (!resp.ok) {
          throw new Error("HTTP " + resp.status + " " + resp.statusText);
        }
        return resp.json();
      })
      .then(function (data) {
        if (seq !== requestSeq) {
          return; // superseded by a newer request
        }
        showLoading(false);
        if (data.error) {
          showBanner("error", data.error);
          return;
        }

        var resolvedBranch = data.resolvedBranch || config.defaultBranch;
        if (data.isFallback) {
          showBanner(
            "warning",
            'Using defaults from "' + resolvedBranch + '" (branch "' + triggerValue + '" not found)',
          );
        } else {
          showBanner("success", "Parameters loaded from branch: " + resolvedBranch);
        }
        applyParams(data.params || []);
      })
      .catch(function (err) {
        if (seq !== requestSeq) {
          return;
        }
        showLoading(false);
        showBanner("error", "Failed to load parameters: " + err.message);
        console.error("[hot-reload-params] Fetch error:", err);
      });
  }

  // ── Core: Apply Parameters (hide/show/create/update/reorder) ─────────────

  function applyParams(paramDefs) {
    // 1. Remove rows created for the previous branch.
    for (var d = 0; d < dynamicRows.length; d++) {
      if (dynamicRows[d].parentNode) {
        dynamicRows[d].parentNode.removeChild(dynamicRows[d]);
      }
    }
    dynamicRows = [];

    var branchParamNames = {};
    for (var i = 0; i < paramDefs.length; i++) {
      branchParamNames[paramDefs[i].name] = true;
    }

    // 2. Hide (and disable inputs of) job parameters NOT in the target branch
    //    (except the trigger). Disabling is essential so they are not submitted.
    var pageRows = getAllParamRows();
    for (var existingName in pageRows) {
      if (!Object.prototype.hasOwnProperty.call(pageRows, existingName)) continue;
      if (existingName === config.triggerParamName) continue;
      setRowVisible(pageRows[existingName], !!branchParamNames[existingName]);
    }

    // 3. Insertion anchor: branch params flow directly after the trigger row.
    var triggerRowInfo = findParamRow(config.triggerParamName);
    var triggerContainer = triggerRowInfo ? triggerRowInfo.container : null;
    var insertionParent = triggerContainer ? triggerContainer.parentNode : root.parentNode;

    // 4. Walk the branch's params in order: update existing rows or create new ones.
    var orderedRows = [];
    for (var j = 0; j < paramDefs.length; j++) {
      var param = paramDefs[j];
      if (!param.name || param.name === config.triggerParamName) continue;

      var existingRow = findParamRow(param.name);
      if (existingRow) {
        setRowVisible(existingRow.container, true);
        if (existingRow.valueInput) {
          updateFieldValue(existingRow.valueInput, param);
        }
        orderedRows.push(existingRow.container);
      } else if (param.type !== "separator") {
        var newRow = createParamRow(param);
        if (newRow && insertionParent) {
          insertionParent.appendChild(newRow);
          dynamicRows.push(newRow);
          orderedRows.push(newRow);
        }
      }
    }

    // 5. Reorder: place each branch param directly after the trigger row.
    if (triggerContainer && triggerContainer.parentNode) {
      var lastPlaced = triggerContainer;
      for (var r = 0; r < orderedRows.length; r++) {
        lastPlaced.parentNode.insertBefore(orderedRows[r], lastPlaced.nextSibling);
        lastPlaced = orderedRows[r];
      }
    }

    // 6. Let Jenkins' own behaviours (checkbox labels, etc.) decorate new rows.
    if (window.Behaviour && typeof Behaviour.applySubtree === "function") {
      for (var b = 0; b < dynamicRows.length; b++) {
        Behaviour.applySubtree(dynamicRows[b], true);
      }
    }

    // 7. Re-anchor the banner/loading right after the trigger row.
    repositionBanner();
  }

  function setRowVisible(container, visible) {
    if (visible) {
      container.classList.remove("jenkins-hidden");
      container.removeAttribute("data-drp-hidden");
    } else {
      container.classList.add("jenkins-hidden");
      container.setAttribute("data-drp-hidden", "true");
    }
    var inputs = container.querySelectorAll("input, select, textarea");
    for (var i = 0; i < inputs.length; i++) {
      inputs[i].disabled = !visible;
    }
  }

  // ── Update a field value ─────────────────────────────────────────────────

  function updateFieldValue(inputEl, param) {
    var newValue = param.defaultValue || "";
    // For imageTag with no explicit defaultTag, don't touch the dropdown.
    if (!newValue && param.type === "imageTag") return;
    if (inputEl.tagName === "SELECT") {
      if (param.type === "choice" && Array.isArray(param.choices)) {
        rebuildSelectOptions(inputEl, param.choices, newValue);
      } else {
        setSelectValue(inputEl, newValue);
      }
    } else if (inputEl.type === "checkbox") {
      inputEl.checked = newValue === "true";
    } else {
      inputEl.value = newValue;
    }
  }

  function setSelectValue(selectEl, value) {
    for (var j = 0; j < selectEl.options.length; j++) {
      if (selectEl.options[j].value === value) {
        selectEl.selectedIndex = j;
        return;
      }
    }
    if (value) {
      var opt = document.createElement("option");
      opt.value = value;
      opt.textContent = value;
      selectEl.appendChild(opt);
      selectEl.value = value;
    }
  }

  function rebuildSelectOptions(selectEl, choices, selected) {
    while (selectEl.firstChild) selectEl.removeChild(selectEl.firstChild);
    for (var i = 0; i < choices.length; i++) {
      var opt = document.createElement("option");
      opt.value = choices[i];
      opt.textContent = choices[i];
      if (choices[i] === selected) opt.selected = true;
      selectEl.appendChild(opt);
    }
  }

  // ── Create new parameter DOM entries ─────────────────────────────────────
  //
  // The markup mirrors what Jenkins core's <f:entry> + parameter index.jelly
  // views produce, so the rows pick up the same styling and alignment:
  //
  //   <div class="jenkins-form-item tr">
  //     <div class="jenkins-form-label help-sibling">NAME</div>
  //     <div class="jenkins-form-description">…</div>
  //     <div class="setting-main">
  //       <div name="parameter">
  //         <input type="hidden" name="name" value="NAME"/>
  //         <input class="jenkins-input" name="value" …/>
  //       </div>
  //     </div>
  //   </div>

  function el(tag, className) {
    var e = document.createElement(tag);
    if (className) e.className = className;
    return e;
  }

  function hiddenInput(name, value) {
    var input = document.createElement("input");
    input.type = "hidden";
    input.name = name;
    input.value = value;
    return input;
  }

  function sanitizeId(name) {
    return String(name == null ? "" : name).replace(/[^A-Za-z0-9_-]/g, "_");
  }

  function badge() {
    var span = el("span", "jenkins-badge jenkins-!-margin-left-1");
    span.textContent = "from branch";
    span.title = "Defined by the parameter file of the selected branch";
    return span;
  }

  function description(text) {
    var div = el("div", "jenkins-form-description");
    div.textContent = text;
    return div;
  }

  function createParamRow(param) {
    var item = el("div", "jenkins-form-item tr");
    item.setAttribute("data-drp-dynamic", "true");

    var isBoolean = param.type === "boolean";
    var isChoice = param.type === "choice" && Array.isArray(param.choices);

    if (!isBoolean) {
      var label = el("div", "jenkins-form-label help-sibling");
      label.textContent = param.name;
      label.appendChild(badge());
      item.appendChild(label);
      if (param.description) item.appendChild(description(param.description));
    }

    var main = el("div", isBoolean ? "setting-main help-sibling" : "setting-main");
    var holder = el("div", isChoice ? "jenkins-select" : "");
    holder.setAttribute("name", "parameter");
    holder.appendChild(hiddenInput("name", param.name));
    holder.appendChild(isBoolean ? buildCheckbox(param) : buildValueControl(param, isChoice));
    main.appendChild(holder);
    item.appendChild(main);

    if (isBoolean && param.description) {
      item.appendChild(description(param.description));
    }
    item.appendChild(el("div", "validation-error-area"));
    return item;
  }

  function buildValueControl(param, isChoice) {
    var control;
    if (isChoice) {
      control = el("select", "jenkins-select__input");
      rebuildSelectOptions(control, param.choices, param.defaultValue || "");
    } else if (param.type === "text") {
      control = el("textarea", "jenkins-input");
      control.rows = 4;
      control.value = param.defaultValue || "";
    } else {
      control = el("input", "jenkins-input");
      control.type = param.type === "password" ? "password" : "text";
      control.value = param.defaultValue || "";
    }
    control.name = "value";
    return control;
  }

  /** Mirrors <f:checkbox title="…"/>: <span class="jenkins-checkbox"><input/><label/></span> */
  function buildCheckbox(param) {
    var span = el("span", "jenkins-checkbox");

    var checkbox = document.createElement("input");
    checkbox.type = "checkbox";
    checkbox.name = "value";
    checkbox.id = "drp-cb-" + sanitizeId(param.name);
    checkbox.checked = param.defaultValue === "true";
    span.appendChild(checkbox);

    // No `for` attribute on purpose: Jenkins' "attach-previous" behaviour
    // already forwards label clicks to the input, and combining both would
    // toggle the box twice (i.e. not at all).
    var label = el("label", "attach-previous");
    label.textContent = param.name;
    label.appendChild(badge());
    span.appendChild(label);

    return span;
  }

  // ── Build-form redirect ──────────────────────────────────────────────────

  /**
   * Point the "Build" form at the plugin's own submission endpoint, carrying
   * over the job name and any `delay` Jenkins put on the original action.
   *
   * Jenkins' native _doBuild rejects any parameter not declared on the job, so
   * a branch that introduces new parameters could never be built without this.
   */
  function redirectBuildForm() {
    var form = root.closest("form") || document.querySelector('form[name="parameters"]');
    if (!form) {
      console.warn("[hot-reload-params] Could not locate the build form");
      return;
    }

    var originalAction = form.getAttribute("action") || "";
    var queryIndex = originalAction.indexOf("?");
    var originalQuery = new URLSearchParams(queryIndex >= 0 ? originalAction.substring(queryIndex + 1) : "");

    var query = new URLSearchParams();
    query.set("job", config.jobFullName);
    var delay = originalQuery.get("delay");
    if (delay) {
      query.set("delay", delay);
    }

    form.setAttribute("action", config.endpointUrl + "/triggerBuild?" + query.toString());
    form.setAttribute("method", "post");
  }

  // ── UI Helpers ────────────────────────────────────────────────────────────

  function showLoading(show) {
    var loading = document.getElementById("drp-loading");
    if (!loading) return;
    loading.classList.toggle("jenkins-hidden", !show);
  }

  var BANNER_VARIANTS = {
    success: "jenkins-alert-success",
    warning: "jenkins-alert-warning",
    error: "jenkins-alert-danger",
    info: "jenkins-alert-info",
  };

  function showBanner(type, message) {
    var banner = document.getElementById("drp-status-banner");
    if (!banner) return;
    banner.textContent = message;
    for (var key in BANNER_VARIANTS) {
      if (Object.prototype.hasOwnProperty.call(BANNER_VARIANTS, key)) {
        banner.classList.remove(BANNER_VARIANTS[key]);
      }
    }
    banner.classList.add(BANNER_VARIANTS[type] || BANNER_VARIANTS.info);
    banner.classList.remove("jenkins-hidden");
    if (type === "success") {
      setTimeout(function () {
        banner.classList.add("jenkins-hidden");
      }, 5000);
    }
  }

  function hideBanner() {
    var banner = document.getElementById("drp-status-banner");
    if (banner) banner.classList.add("jenkins-hidden");
  }

  // ── Public API ───────────────────────────────────────────────────────────

  window.HotReloadParams = {
    reload: function () {
      var triggerInput = findParamValueInput(config.triggerParamName);
      var value = triggerInput && triggerInput.value ? triggerInput.value.trim() : "";
      lastRequestedValue = null;
      fetchAndApply(value || config.defaultBranch);
    },
  };

  // ── Bootstrap ────────────────────────────────────────────────────────────

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
