/**
 * hot-reload-params.js
 *
 * Hot-reloads the entire parameters list on the "Build with Parameters" page.
 * When the trigger parameter (e.g. RELEASE_BRANCH) changes, the plugin:
 *   1. Fetches the pipeline file from the matching Git branch
 *   2. Parses all parameter definitions from that branch
 *   3. Hides (and disables) parameters that don't exist on the target branch
 *   4. Shows / creates parameters that do exist on the target branch
 *   5. Reorders rows so DOM order matches the branch's parameter order
 *   6. Redirects the "Build" form to the plugin's submission endpoint so
 *      the build can succeed even when the branch introduces parameters the
 *      job's stored ParametersDefinitionProperty doesn't know about.
 */
(function () {
  "use strict";

  var root = null;
  var config = {};
  var debounceTimer = null;
  var DEBOUNCE_MS = 800;
  // Track dynamically-created rows so we can clean them up on re-fetch
  var dynamicRows = [];

  var TRIGGER_BUILD_URL_SUFFIX =
    "/descriptorByName/io.github.zeeshan.hotreloadparams.HotReloadParameterDefinition/triggerBuild";

  // ── Initialization ───────────────────────────────────────────────────────

  function init() {
    root = document.getElementById("hot-reload-params-root");
    if (!root) {
      setTimeout(init, 200);
      return;
    }

    config = {
      repoUrl: root.getAttribute("data-repo-url"),
      credentialsId: root.getAttribute("data-credentials-id") || "",
      paramFilePath:
        root.getAttribute("data-param-file-path") ||
        "vars/release-pipeline.groovy",
      triggerParamName:
        root.getAttribute("data-trigger-param-name") || "RELEASE_BRANCH",
      defaultBranch: root.getAttribute("data-default-branch") || "master",
      descriptorUrl: root.getAttribute("data-descriptor-url"),
      jobFullName: root.getAttribute("data-job-full-name") || "",
    };

    attachTriggerListener();
    repositionBanner();
    hijackBuildForm();

    // Initial load
    var triggerInput = findParamValueInput(config.triggerParamName);
    if (triggerInput) {
      var initialValue = triggerInput.value;
      if (
        initialValue &&
        initialValue !== "" &&
        initialValue.indexOf("vX.Y.Z") === -1
      ) {
        fetchAndApply(initialValue);
      } else {
        fetchAndApply(config.defaultBranch);
      }
    } else {
      fetchAndApply(config.defaultBranch);
    }
  }

  // ── Locate Parameter Elements ────────────────────────────────────────────

  /**
   * Find the container (row/form-item) for a Jenkins parameter by its name.
   * Returns { container, valueInput } or null.
   */
  function findParamRow(paramName) {
    var nameInputs = document.querySelectorAll('input[name="name"]');
    for (var i = 0; i < nameInputs.length; i++) {
      if (nameInputs[i].value === paramName) {
        var container = closestParamContainer(nameInputs[i]);
        if (container) {
          var valueEl =
            container.querySelector('input[name="value"]') ||
            container.querySelector('select[name="value"]') ||
            container.querySelector('textarea[name="value"]');
          // Fallback: any non-hidden input/select
          if (!valueEl) {
            var allInputs = container.querySelectorAll(
              "input, select, textarea",
            );
            for (var k = 0; k < allInputs.length; k++) {
              var el = allInputs[k];
              if (el.name === "name" || el.type === "hidden") continue;
              if (
                el.tagName === "SELECT" ||
                el.type === "text" ||
                el.tagName === "TEXTAREA"
              ) {
                valueEl = el;
                break;
              }
            }
          }
          return { container: container, valueInput: valueEl };
        }
      }
    }
    return null;
  }

  /**
   * Return the outer container that wraps BOTH the label/description AND the
   * <div name="parameter"> inner block. In modern Jenkins this is
   * <div class="jenkins-form-item">. Using `[name="parameter"]` (the inner
   * block) would strand the label when we move the row.
   */
  function closestParamContainer(el) {
    return (
      el.closest(".jenkins-form-item") ||
      el.closest('[name="parameter"]') ||
      el.closest("tr") ||
      el.parentElement
    );
  }

  function findParamValueInput(paramName) {
    var row = findParamRow(paramName);
    return row ? row.valueInput : null;
  }

  /**
   * Get all existing parameter containers keyed by name.
   */
  function getAllParamRows() {
    var map = {};
    var nameInputs = document.querySelectorAll('input[name="name"]');
    for (var i = 0; i < nameInputs.length; i++) {
      var name = nameInputs[i].value;
      var container = closestParamContainer(nameInputs[i]);
      if (container) {
        map[name] = container;
      }
    }
    return map;
  }

  /**
   * The plugin's own marker row — the one containing
   * <input name="name" value="HOT_RELOAD_PARAMS">.
   * Used as the insertion anchor for dynamic rows so they land in the
   * correct position regardless of how Jenkins wraps the params list.
   */
  function findPluginRow() {
    var nameInputs = document.querySelectorAll('input[name="name"]');
    for (var i = 0; i < nameInputs.length; i++) {
      if (nameInputs[i].value === "HOT_RELOAD_PARAMS") {
        return closestParamContainer(nameInputs[i]);
      }
    }
    // Last-resort fallback: the root element's nearest row
    return root ? closestParamContainer(root) : null;
  }

  // ── Trigger Listener ─────────────────────────────────────────────────────

  /**
   * Move the banner and loading indicator right after the trigger parameter row.
   */
  function repositionBanner() {
    var triggerRow = findParamRow(config.triggerParamName);
    if (!triggerRow) return;
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
      console.warn("[DRP] Trigger input not found: " + config.triggerParamName);
      return;
    }

    var handler = function () {
      clearTimeout(debounceTimer);
      debounceTimer = setTimeout(function () {
        var value = triggerInput.value;
        if (value && value.trim() !== "") {
          fetchAndApply(value.trim());
        }
      }, DEBOUNCE_MS);
    };

    triggerInput.addEventListener("change", handler);
    triggerInput.addEventListener("blur", handler);
    triggerInput.addEventListener("input", handler);
  }

  // ── AJAX Fetch ───────────────────────────────────────────────────────────

  function fetchAndApply(triggerValue) {
    showLoading(true);
    hideBanner();

    var crumb = getCrumb();
    var url =
      getRootUrl() +
      "/descriptorByName/io.github.zeeshan.hotreloadparams.HotReloadParameterDefinition/fetchParams" +
      "?triggerValue=" +
      encodeURIComponent(triggerValue) +
      "&repoUrl=" +
      encodeURIComponent(config.repoUrl) +
      "&credentialsId=" +
      encodeURIComponent(config.credentialsId) +
      "&paramFilePath=" +
      encodeURIComponent(config.paramFilePath) +
      "&defaultBranch=" +
      encodeURIComponent(config.defaultBranch);

    fetch(url, {
      method: "GET",
      headers: crumb ? { [crumb.name]: crumb.value } : {},
    })
      .then(function (resp) {
        return resp.json();
      })
      .then(function (data) {
        showLoading(false);
        if (data.error) {
          showBanner("error", data.error);
          return;
        }

        var resolvedBranch = data.resolvedBranch || config.defaultBranch;
        if (data.isFallback) {
          showBanner(
            "warning",
            'Using defaults from "' +
              resolvedBranch +
              '" (branch "' +
              triggerValue +
              '" not found)',
          );
        } else {
          showBanner(
            "success",
            "Parameters loaded from branch: " + resolvedBranch,
          );
        }

        console.info(
          "[DRP] Received " +
            (data.params || []).length +
            " params from " +
            resolvedBranch,
        );
        applyParams(data.params || []);
      })
      .catch(function (err) {
        showLoading(false);
        showBanner("error", "Failed to fetch: " + err.message);
        console.error("[DRP] Fetch error:", err);
      });
  }

  // ── Core: Apply Parameters (hide/show/create/update/reorder) ─────────────

  function applyParams(paramDefs) {
    // Build a set of param names from the target branch (skip separators for hiding logic)
    var branchParamNames = {};
    for (var i = 0; i < paramDefs.length; i++) {
      branchParamNames[paramDefs[i].name] = true;
    }

    // Get all current param rows on the page
    var pageRows = getAllParamRows();

    // 1. Remove previously created dynamic rows
    for (var d = 0; d < dynamicRows.length; d++) {
      if (dynamicRows[d].parentNode) {
        dynamicRows[d].parentNode.removeChild(dynamicRows[d]);
      }
    }
    dynamicRows = [];

    // 2. Hide (and disable inputs of) existing params NOT in the target branch
    //    (except trigger + plugin itself). Disabling is essential so they don't
    //    submit as form values when the user clicks Build.
    var hiddenCount = 0;
    for (var existingName in pageRows) {
      if (!pageRows.hasOwnProperty(existingName)) continue;
      if (existingName === config.triggerParamName) continue;
      if (existingName === "HOT_RELOAD_PARAMS") continue;
      if (branchParamNames[existingName]) {
        pageRows[existingName].style.display = "";
        pageRows[existingName].removeAttribute("data-drp-hidden");
        setInputsDisabled(pageRows[existingName], false);
      } else {
        pageRows[existingName].style.display = "none";
        pageRows[existingName].setAttribute("data-drp-hidden", "true");
        setInputsDisabled(pageRows[existingName], true);
        hiddenCount++;
      }
    }

    // 3. Insertion anchor: the trigger row. Branch params should flow directly
    //    after the trigger (e.g. RELEASE_BRANCH), regardless of where the user
    //    placed the HOT_RELOAD_PARAMS marker in the job's parameter list.
    var triggerRowInfo = findParamRow(config.triggerParamName);
    var triggerContainer = triggerRowInfo ? triggerRowInfo.container : null;
    var pluginRow = findPluginRow();
    var insertionParent =
      (triggerContainer && triggerContainer.parentNode) ||
      (pluginRow && pluginRow.parentNode) ||
      null;

    // 4. Iterate params from branch in order: update existing or create new
    var updatedCount = 0;
    var createdCount = 0;
    var orderedRows = [];

    for (var j = 0; j < paramDefs.length; j++) {
      var param = paramDefs[j];
      if (!param.name) continue;
      if (param.name === config.triggerParamName) {
        // Keep the trigger row in its natural spot; don't move it.
        continue;
      }

      var existingRow = findParamRow(param.name);

      if (existingRow) {
        existingRow.container.style.display = "";
        existingRow.container.removeAttribute("data-drp-hidden");
        setInputsDisabled(existingRow.container, false);
        if (existingRow.valueInput) {
          updateFieldValue(existingRow.valueInput, param);
        }
        setDrpType(existingRow.container, param.type);
        orderedRows.push(existingRow.container);
        updatedCount++;
      } else if (param.type !== "separator") {
        var newRow = createParamRow(param);
        if (newRow && insertionParent) {
          // Temporarily attach; step 5 places it in the correct position.
          insertionParent.appendChild(newRow);
          dynamicRows.push(newRow);
          orderedRows.push(newRow);
          createdCount++;
        }
      }
    }

    // 5. Reorder: place each branch param directly after the trigger row,
    //    preserving branch order. Fallback to inserting before the plugin row
    //    if the trigger can't be located for some reason.
    if (triggerContainer && triggerContainer.parentNode) {
      var lastPlaced = triggerContainer;
      for (var r = 0; r < orderedRows.length; r++) {
        lastPlaced.parentNode.insertBefore(
          orderedRows[r],
          lastPlaced.nextSibling,
        );
        lastPlaced = orderedRows[r];
      }
    } else if (pluginRow && pluginRow.parentNode) {
      for (var r2 = 0; r2 < orderedRows.length; r2++) {
        pluginRow.parentNode.insertBefore(orderedRows[r2], pluginRow);
      }
    }

    // 6. Re-anchor the banner/loading right after the trigger row. Step 5
    //    inserts param rows between the trigger and whatever currently sits
    //    after it, which shoves the banner to the bottom of the params list.
    repositionBanner();

    console.info(
      "[DRP] Updated: " +
        updatedCount +
        ", Created: " +
        createdCount +
        ", Hidden: " +
        hiddenCount,
    );
  }

  // ── Update a field value ─────────────────────────────────────────────────

  function updateFieldValue(inputEl, param) {
    var newValue = param.defaultValue || "";
    // For imageTag with no explicit defaultTag, don't touch the dropdown
    if (!newValue && param.type === "imageTag") return;
    if (inputEl.tagName === "SELECT") {
      // For choice params, rebuild the options from the branch's choices list
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
    var found = false;
    for (var j = 0; j < selectEl.options.length; j++) {
      if (selectEl.options[j].value === value) {
        selectEl.selectedIndex = j;
        found = true;
        break;
      }
    }
    if (!found && value) {
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
    if (selected && choices.indexOf(selected) === -1) {
      var extra = document.createElement("option");
      extra.value = selected;
      extra.textContent = selected;
      extra.selected = true;
      selectEl.appendChild(extra);
    }
  }

  /**
   * Ensure the container has a hidden <input name="drpType"> carrying the
   * branch's declared type. The server uses this to construct the right
   * ParameterValue subclass at build time, independent of whatever definition
   * the job's stored config has (or doesn't have).
   */
  function setDrpType(container, type) {
    if (!container || !type) return;
    var existing = container.querySelector('input[name="drpType"]');
    if (existing) {
      existing.value = type;
      return;
    }
    var hidden = document.createElement("input");
    hidden.type = "hidden";
    hidden.name = "drpType";
    hidden.value = type;
    container.appendChild(hidden);
  }

  function setInputsDisabled(container, disabled) {
    if (!container) return;
    var inputs = container.querySelectorAll("input, select, textarea");
    for (var i = 0; i < inputs.length; i++) {
      inputs[i].disabled = !!disabled;
    }
  }

  // ── Create new parameter DOM entries ─────────────────────────────────────

  function createParamRow(param) {
    var wrapper = document.createElement("div");
    wrapper.className = "jenkins-form-item tr";
    wrapper.setAttribute("name", "parameter");
    wrapper.setAttribute("data-drp-dynamic", "true");

    // Hidden "name" input (required for Jenkins form submission)
    var nameInput = document.createElement("input");
    nameInput.type = "hidden";
    nameInput.name = "name";
    nameInput.value = param.name;
    wrapper.appendChild(nameInput);

    // Hidden drpType input — tells the plugin's server-side endpoint how to
    // coerce the value.
    var typeInput = document.createElement("input");
    typeInput.type = "hidden";
    typeInput.name = "drpType";
    typeInput.value = param.type || "string";
    wrapper.appendChild(typeInput);

    if (param.type === "boolean") {
      buildBooleanRow(wrapper, param);
    } else {
      appendLabel(wrapper, param);
      var valueInput;
      if (param.type === "choice" && Array.isArray(param.choices)) {
        var selectWrapper = document.createElement("div");
        selectWrapper.className = "jenkins-select";
        valueInput = document.createElement("select");
        valueInput.name = "value";
        valueInput.className = "jenkins-select__input";
        rebuildSelectOptions(valueInput, param.choices, param.defaultValue || "");
        selectWrapper.appendChild(valueInput);
        wrapper.appendChild(selectWrapper);
      } else if (param.type === "text") {
        valueInput = document.createElement("textarea");
        valueInput.name = "value";
        valueInput.value = param.defaultValue || "";
        valueInput.className = "jenkins-input";
        valueInput.rows = 4;
        wrapper.appendChild(valueInput);
      } else {
        valueInput = document.createElement("input");
        valueInput.type = param.type === "password" ? "password" : "text";
        valueInput.name = "value";
        valueInput.value = param.defaultValue || "";
        valueInput.className = "jenkins-input";
        wrapper.appendChild(valueInput);
      }
      appendBadge(wrapper);
    }

    return wrapper;
  }

  function appendLabel(wrapper, param) {
    var label = document.createElement("div");
    label.className = "jenkins-form-label";
    label.textContent = param.name;
    if (param.description) {
      var desc = document.createElement("div");
      desc.className = "jenkins-form-description";
      desc.textContent = param.description;
      label.appendChild(desc);
    }
    wrapper.appendChild(label);
  }

  function appendBadge(wrapper) {
    var badge = document.createElement("span");
    badge.className = "jenkins-badge jenkins-!-margin-left-1";
    badge.textContent = "from branch";
    wrapper.appendChild(badge);
  }

  /**
   * Booleans render as a Jenkins toggle checkbox: a wrapping <label> with the
   * checkbox followed by the parameter name. Mirrors the markup Jenkins core
   * emits for a BooleanParameterDefinition so the toggle picks up native theme
   * styling rather than the default browser checkbox.
   */
  function buildBooleanRow(wrapper, param) {
    var checkboxRow = document.createElement("div");
    checkboxRow.className = "jenkins-checkbox";

    var checkbox = document.createElement("input");
    checkbox.type = "checkbox";
    checkbox.name = "value";
    checkbox.id = "drp-cb-" + param.name;
    checkbox.checked = param.defaultValue === "true";
    checkboxRow.appendChild(checkbox);

    var labelEl = document.createElement("label");
    labelEl.setAttribute("for", checkbox.id);
    labelEl.className = "jenkins-checkbox__label";
    labelEl.textContent = param.name;
    checkboxRow.appendChild(labelEl);

    wrapper.appendChild(checkboxRow);

    if (param.description) {
      var desc = document.createElement("div");
      desc.className = "jenkins-form-description";
      desc.textContent = param.description;
      wrapper.appendChild(desc);
    }

    appendBadge(wrapper);
  }

  // ── Build-form hijack ────────────────────────────────────────────────────

  /**
   * Redirect the "Build" form to the plugin's own submission endpoint and add
   * a hidden drpJobFullName input so the server knows which job to schedule.
   *
   * Without this, Jenkins' native _doBuild rejects any parameter not declared
   * on the job, so a branch that introduces new parameters would always fail.
   */
  function hijackBuildForm() {
    var pluginRow = findPluginRow();
    var form = pluginRow ? pluginRow.closest("form") : null;
    if (!form) {
      // Fall back to the first form on the page that posts to a /build endpoint
      var forms = document.querySelectorAll("form");
      for (var i = 0; i < forms.length; i++) {
        var action = forms[i].getAttribute("action") || "";
        if (/\/build(WithParameters)?(\/|$|\?)/.test(action)) {
          form = forms[i];
          break;
        }
      }
    }
    if (!form) {
      console.warn("[DRP] Could not locate build form to hijack");
      return;
    }

    form.setAttribute("action", getRootUrl() + TRIGGER_BUILD_URL_SUFFIX);
    form.setAttribute("method", "post");

    var jobFullName = getJobFullName();
    var existing = form.querySelector('input[name="drpJobFullName"]');
    if (existing) {
      existing.value = jobFullName;
    } else {
      var hidden = document.createElement("input");
      hidden.type = "hidden";
      hidden.name = "drpJobFullName";
      hidden.value = jobFullName;
      form.appendChild(hidden);
    }
  }

  /**
   * The job's full name comes straight from the Jelly view as a data attribute.
   * Avoids parsing window.location, which would break under non-standard URL
   * prefixes (e.g. `mvn hpi:run` serves Jenkins at `/jenkins/`).
   */
  function getJobFullName() {
    return config.jobFullName || "";
  }

  // ── UI Helpers ────────────────────────────────────────────────────────────

  function showLoading(show) {
    var el = document.getElementById("drp-loading");
    if (!el) return;
    if (show) {
      el.classList.remove("jenkins-hidden");
    } else {
      el.classList.add("jenkins-hidden");
    }
  }

  var BANNER_VARIANTS = [
    "jenkins-alert-success",
    "jenkins-alert-warning",
    "jenkins-alert-danger",
    "jenkins-alert-info",
  ];

  function showBanner(type, message) {
    var banner = document.getElementById("drp-status-banner");
    if (!banner) return;
    banner.textContent = message;
    for (var i = 0; i < BANNER_VARIANTS.length; i++) {
      banner.classList.remove(BANNER_VARIANTS[i]);
    }
    switch (type) {
      case "success":
        banner.classList.add("jenkins-alert-success");
        break;
      case "warning":
        banner.classList.add("jenkins-alert-warning");
        break;
      case "error":
        banner.classList.add("jenkins-alert-danger");
        break;
      default:
        banner.classList.add("jenkins-alert-info");
    }
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

  function getCrumb() {
    var crumbMeta = document.querySelector('meta[name="crumb.field"]');
    var crumbValueMeta = document.querySelector('meta[name="crumb.value"]');
    if (crumbMeta && crumbValueMeta) {
      return { name: crumbMeta.content, value: crumbValueMeta.content };
    }
    if (window.crumb) {
      return {
        name: window.crumb.fieldName || ".crumb",
        value: window.crumb.value,
      };
    }
    return null;
  }

  function getRootUrl() {
    if (document.head && document.head.dataset && document.head.dataset.rooturl) {
      return document.head.dataset.rooturl;
    }
    var rootUrl = document.head
      ? document.head.querySelector('meta[name="rootURL"]')
      : null;
    if (rootUrl) return rootUrl.content;
    return window.rootURL || "";
  }

  // ── Public API ───────────────────────────────────────────────────────────

  window.HotReloadParams = {
    reload: function () {
      var triggerInput = findParamValueInput(config.triggerParamName);
      var value = triggerInput ? triggerInput.value : config.defaultBranch;
      fetchAndApply(value || config.defaultBranch);
    },
  };

  // ── Bootstrap ────────────────────────────────────────────────────────────

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", function () {
      setTimeout(init, 300);
    });
  } else {
    setTimeout(init, 300);
  }
})();
