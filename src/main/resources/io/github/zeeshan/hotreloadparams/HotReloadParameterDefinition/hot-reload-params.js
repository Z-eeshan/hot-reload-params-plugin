/**
 * hot-reload-params.js
 *
 * Hot-reloads the entire parameters list on the "Build with Parameters" page.
 * When the trigger parameter (e.g. RELEASE_BRANCH) changes, the plugin:
 *   1. Fetches the pipeline file from the matching Git branch
 *   2. Parses all parameter definitions from that branch
 *   3. Hides parameters that don't exist on the target branch
 *   4. Shows / creates parameters that do exist on the target branch
 *   5. Updates default values for matching parameters
 */
(function () {
  "use strict";

  var root = null;
  var config = {};
  var debounceTimer = null;
  var DEBOUNCE_MS = 800;
  // Track dynamically-created rows so we can clean them up on re-fetch
  var dynamicRows = [];

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
    };

    attachTriggerListener();
    repositionBanner();

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
        var container =
          nameInputs[i].closest(".jenkins-form-item") ||
          nameInputs[i].closest('[name="parameter"]') ||
          nameInputs[i].closest("tr") ||
          nameInputs[i].parentElement;
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
      var container =
        nameInputs[i].closest(".jenkins-form-item") ||
        nameInputs[i].closest('[name="parameter"]') ||
        nameInputs[i].closest("tr") ||
        nameInputs[i].parentElement;
      if (container) {
        map[name] = container;
      }
    }
    return map;
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

  // ── Core: Apply Parameters (hide/show/create/update) ─────────────────────

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

    // 2. Hide existing params NOT in the target branch (except trigger + plugin itself)
    var hiddenCount = 0;
    for (var existingName in pageRows) {
      if (!pageRows.hasOwnProperty(existingName)) continue;
      if (existingName === config.triggerParamName) continue;
      // Don't hide separators, booleans etc that we control
      if (branchParamNames[existingName]) {
        // Show it (might have been hidden before)
        pageRows[existingName].style.display = "";
        pageRows[existingName].removeAttribute("data-drp-hidden");
      } else {
        // Hide it
        pageRows[existingName].style.display = "none";
        pageRows[existingName].setAttribute("data-drp-hidden", "true");
        hiddenCount++;
      }
    }

    // 3. Find the insertion anchor — we insert new dynamic rows before the plugin's own root
    var insertionAnchor =
      root.closest(".jenkins-form-item") ||
      root.closest('[name="parameter"]') ||
      root.closest("tr") ||
      root.parentElement;

    // 4. Iterate params from branch: update existing or create new
    var updatedCount = 0;
    var createdCount = 0;

    for (var j = 0; j < paramDefs.length; j++) {
      var param = paramDefs[j];
      if (!param.name) continue;
      if (param.name === config.triggerParamName) continue;

      var existingRow = findParamRow(param.name);

      if (existingRow) {
        // Parameter exists on the page — update its value
        existingRow.container.style.display = "";
        existingRow.container.removeAttribute("data-drp-hidden");
        if (existingRow.valueInput) {
          updateFieldValue(existingRow.valueInput, param);
        }
        updatedCount++;
      } else if (param.type !== "separator") {
        // Parameter doesn't exist on the page — create a new form entry
        var newRow = createParamRow(param);
        if (newRow && insertionAnchor && insertionAnchor.parentNode) {
          insertionAnchor.parentNode.insertBefore(newRow, insertionAnchor);
          dynamicRows.push(newRow);
          createdCount++;
        }
      }
    }

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
      setSelectValue(inputEl, newValue);
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

  // ── Create new parameter DOM entries ─────────────────────────────────────

  function createParamRow(param) {
    // Create a Jenkins-style parameter entry
    var wrapper = document.createElement("div");
    wrapper.className = "jenkins-form-item tr";
    wrapper.setAttribute("name", "parameter");
    wrapper.setAttribute("data-drp-dynamic", "true");
    wrapper.style.padding = "8px 0";

    // Hidden "name" input (required for Jenkins form submission)
    var nameInput = document.createElement("input");
    nameInput.type = "hidden";
    nameInput.name = "name";
    nameInput.value = param.name;
    wrapper.appendChild(nameInput);

    // Label
    var label = document.createElement("div");
    label.style.cssText =
      "font-weight: bold; margin-bottom: 4px; font-size: 13px;";
    label.textContent = param.name;
    if (param.description) {
      var desc = document.createElement("span");
      desc.style.cssText =
        "font-weight: normal; color: #666; margin-left: 8px; font-size: 12px;";
      desc.textContent = param.description;
      label.appendChild(desc);
    }
    wrapper.appendChild(label);

    // Value input
    var valueInput;

    if (param.type === "boolean") {
      valueInput = document.createElement("input");
      valueInput.type = "checkbox";
      valueInput.name = "value";
      valueInput.checked = param.defaultValue === "true";
    } else {
      valueInput = document.createElement("input");
      valueInput.type = "text";
      valueInput.name = "value";
      valueInput.value = param.defaultValue || "";
      valueInput.className = "jenkins-input";
      valueInput.style.cssText =
        "width: 100%; max-width: 500px; padding: 6px 8px; border: 1px solid #ccc; border-radius: 4px;";
    }

    wrapper.appendChild(valueInput);

    // Dynamic badge
    var badge = document.createElement("span");
    badge.style.cssText =
      "display: inline-block; margin-left: 8px; padding: 1px 6px; font-size: 10px; background: #e3f2fd; color: #1565c0; border-radius: 3px;";
    badge.textContent = "from branch";
    wrapper.appendChild(badge);

    return wrapper;
  }

  // ── UI Helpers ────────────────────────────────────────────────────────────

  function showLoading(show) {
    var el = document.getElementById("drp-loading");
    if (el) el.style.display = show ? "block" : "none";
  }

  function showBanner(type, message) {
    var banner = document.getElementById("drp-status-banner");
    if (!banner) return;
    banner.style.display = "block";
    banner.textContent = message;
    switch (type) {
      case "success":
        banner.style.background = "#e6f4ea";
        banner.style.color = "#137333";
        banner.style.border = "1px solid #a8dab5";
        break;
      case "warning":
        banner.style.background = "#fef7e0";
        banner.style.color = "#b45309";
        banner.style.border = "1px solid #fcd34d";
        break;
      case "error":
        banner.style.background = "#fce8e6";
        banner.style.color = "#c5221f";
        banner.style.border = "1px solid #f5c6cb";
        break;
    }
    if (type === "success") {
      setTimeout(function () {
        banner.style.display = "none";
      }, 5000);
    }
  }

  function hideBanner() {
    var banner = document.getElementById("drp-status-banner");
    if (banner) banner.style.display = "none";
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
    var rootUrl = document.head.querySelector('meta[name="rootURL"]');
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
