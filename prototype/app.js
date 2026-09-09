(() => {
  "use strict";

  const routeTitles = {
    monitor: "监督计划",
    focus: "专注",
    workbench: "清单",
    statistics: "统计",
    profile: "我的"
  };

  const root = document.documentElement;
  const main = document.querySelector("#main-content");
  const topTitle = document.querySelector("#top-title");
  const toast = document.querySelector("#toast");
  let toastTimer = 0;

  function refreshIcons() {
    if (window.lucide) {
      window.lucide.createIcons({ attrs: { "stroke-width": 1.8 } });
    }
  }

  function showToast(message) {
    window.clearTimeout(toastTimer);
    toast.textContent = message;
    toast.hidden = false;
    toastTimer = window.setTimeout(() => {
      toast.hidden = true;
    }, 3200);
  }

  function showRoute(route, moveFocus = true) {
    const resolvedRoute = routeTitles[route] ? route : "monitor";
    document.querySelectorAll("[data-view]").forEach((view) => {
      const isActive = view.dataset.view === resolvedRoute;
      view.hidden = !isActive;
      view.classList.toggle("is-active", isActive);
    });

    document.querySelectorAll("[data-route]").forEach((button) => {
      const isActive = button.dataset.route === resolvedRoute;
      button.classList.toggle("is-active", isActive);
      if (isActive) {
        button.setAttribute("aria-current", "page");
      } else {
        button.removeAttribute("aria-current");
      }
    });

    topTitle.textContent = routeTitles[resolvedRoute];
    document.title = `${routeTitles[resolvedRoute]} - 玩机有度原型`;
    if (window.location.hash.slice(1) !== resolvedRoute) {
      window.location.hash = resolvedRoute;
    }
    if (moveFocus) {
      main.focus({ preventScroll: true });
      const scrollBehavior = window.matchMedia("(prefers-reduced-motion: reduce)").matches
        ? "auto"
        : "smooth";
      window.scrollTo({ top: 0, behavior: scrollBehavior });
    }
  }

  document.querySelectorAll("[data-route]").forEach((button) => {
    button.addEventListener("click", () => showRoute(button.dataset.route));
  });

  window.addEventListener("hashchange", () => {
    showRoute(window.location.hash.slice(1), false);
  });

  function activateTab(tabButton) {
    const tabList = tabButton.closest("[data-tabs]");
    if (!tabList) return;
    const tabs = [...tabList.querySelectorAll('[role="tab"]')];
    tabs.forEach((tab) => {
      const selected = tab === tabButton;
      tab.setAttribute("aria-selected", String(selected));
      tab.tabIndex = selected ? 0 : -1;
      const panel = document.getElementById(tab.dataset.tabTarget);
      if (panel) panel.hidden = !selected;
    });
  }

  document.querySelectorAll("[data-tabs]").forEach((tabList) => {
    const tabs = [...tabList.querySelectorAll('[role="tab"]')];
    tabs.forEach((tab, index) => {
      tab.tabIndex = tab.getAttribute("aria-selected") === "true" ? 0 : -1;
      tab.addEventListener("click", () => activateTab(tab));
      tab.addEventListener("keydown", (event) => {
        if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
        event.preventDefault();
        let nextIndex = index;
        if (event.key === "ArrowRight") nextIndex = (index + 1) % tabs.length;
        if (event.key === "ArrowLeft") nextIndex = (index - 1 + tabs.length) % tabs.length;
        if (event.key === "Home") nextIndex = 0;
        if (event.key === "End") nextIndex = tabs.length - 1;
        activateTab(tabs[nextIndex]);
        tabs[nextIndex].focus();
      });
    });
  });

  function safeStorageGet(key) {
    try {
      return window.localStorage.getItem(key);
    } catch (_) {
      return null;
    }
  }

  function safeStorageSet(key, value) {
    try {
      window.localStorage.setItem(key, value);
    } catch (_) {
      // Local files can disable storage; the current session still keeps the theme.
    }
  }

  function applyTheme(theme, announce = false) {
    const resolved = theme === "light" ? "light" : "dark";
    root.dataset.theme = resolved;
    safeStorageSet("controlfree-prototype-theme", resolved);
    const value = document.querySelector("#theme-setting-value");
    if (value) value.textContent = resolved === "dark" ? "深色" : "亮色";
    const label = resolved === "dark" ? "切换为亮色主题" : "切换为深色主题";
    document.querySelector("#quick-theme-toggle").setAttribute("aria-label", label);
    if (announce) showToast(`已切换为${resolved === "dark" ? "深色" : "亮色"}主题`);
  }

  function toggleTheme() {
    applyTheme(root.dataset.theme === "dark" ? "light" : "dark", true);
  }

  document.querySelector("#quick-theme-toggle").addEventListener("click", toggleTheme);
  document.querySelector("#theme-setting").addEventListener("click", toggleTheme);

  document.querySelectorAll("[data-toast]").forEach((button) => {
    button.addEventListener("click", () => showToast(button.dataset.toast));
  });

  function updateEnabledPlanCount() {
    const enabledCount = [...document.querySelectorAll("[data-plan-toggle]")]
      .filter((toggle) => toggle.checked).length;
    document.querySelector("#enabled-plan-count").textContent = String(enabledCount);
  }

  document.addEventListener("change", (event) => {
    const toggle = event.target.closest("[data-plan-toggle]");
    if (!toggle) return;
    const state = toggle.closest(".plan-row")?.querySelector(".state-label");
    if (state) {
      state.className = `state-label ${toggle.checked ? toggle.dataset.activeTone || "success" : "muted"}`;
      state.textContent = toggle.checked ? toggle.dataset.activeLabel || "已启用" : "已停用";
    }
    updateEnabledPlanCount();
    showToast(`${toggle.dataset.planToggle}已${toggle.checked ? "启用" : "停用"}`);
  });

  const valueLimits = {
    "lock-value": { min: 1, max: 180 },
    "play-value": { min: 1, max: 60 }
  };

  function setDuration(id, value) {
    const output = document.getElementById(id);
    const limit = valueLimits[id];
    if (!output || !limit) return;
    const normalized = Math.min(limit.max, Math.max(limit.min, Number(value)));
    output.value = `${normalized} 分钟`;
    output.textContent = `${normalized} 分钟`;
  }

  function durationValue(id) {
    return Number.parseInt(document.getElementById(id).textContent, 10) || 1;
  }

  document.querySelectorAll("[data-step-target]").forEach((button) => {
    button.addEventListener("click", () => {
      const id = button.dataset.stepTarget;
      setDuration(id, durationValue(id) + Number(button.dataset.step));
      document.querySelectorAll("[data-focus-preset]").forEach((preset) => {
        preset.classList.remove("is-selected");
        preset.setAttribute("aria-pressed", "false");
      });
    });
  });

  document.querySelectorAll("[data-focus-preset]").forEach((button) => {
    button.addEventListener("click", () => {
      document.querySelectorAll("[data-focus-preset]").forEach((preset) => {
        const selected = preset === button;
        preset.classList.toggle("is-selected", selected);
        preset.setAttribute("aria-pressed", String(selected));
      });
      setDuration("lock-value", button.dataset.lock);
      setDuration("play-value", button.dataset.play);
    });
  });

  document.querySelectorAll("[data-load-focus]").forEach((button) => {
    button.addEventListener("click", () => {
      document.querySelector("#focus-task").value = button.dataset.loadFocus;
      setDuration("lock-value", button.dataset.lock);
      setDuration("play-value", button.dataset.play);
      showToast(`已载入“${button.dataset.loadFocus}”`);
    });
  });

  let focusInterval = 0;
  let focusRemainingSeconds = 0;
  let focusPauseRemainingSeconds = 0;
  let focusPaused = false;
  let focusPhase = "lock";
  const composer = document.querySelector("#focus-composer");
  const running = document.querySelector("#focus-running");
  const countdown = document.querySelector("#focus-countdown");
  const runningTitle = document.querySelector("#running-title");
  const phaseLabel = document.querySelector("#focus-phase-label");
  const runningCopy = document.querySelector("#running-copy");
  const focusAnnouncement = document.querySelector("#focus-announcement");
  const pauseButton = document.querySelector("#pause-focus");

  function updateCountdown() {
    const remainingSeconds = focusPaused ? focusPauseRemainingSeconds : focusRemainingSeconds;
    const minutes = Math.floor(remainingSeconds / 60);
    const seconds = remainingSeconds % 60;
    countdown.textContent = `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
  }

  function renderFocusState(announce = false) {
    let icon = "lock-keyhole";
    let announcement = "";

    if (focusPaused) {
      running.dataset.phase = "pause";
      phaseLabel.textContent = "临时暂停";
      runningCopy.textContent = "身份已验证，当前阶段计时已冻结";
      pauseButton.innerHTML = '<i data-lucide="play" aria-hidden="true"></i>继续专注';
      icon = "shield-check";
      announcement = `专注已暂停 ${Math.ceil(focusPauseRemainingSeconds / 60)} 分钟`;
    } else if (focusPhase === "play") {
      running.dataset.phase = "play";
      phaseLabel.textContent = "玩机阶段";
      runningCopy.textContent = `结束后自动进入 ${durationValue("lock-value")} 分钟锁定`;
      pauseButton.innerHTML = '<i data-lucide="shield-alert" aria-hidden="true"></i>申请暂停';
      icon = "smartphone";
      announcement = `已进入玩机阶段，共 ${durationValue("play-value")} 分钟`;
    } else {
      running.dataset.phase = "lock";
      phaseLabel.textContent = "锁定阶段";
      runningCopy.textContent = `结束后将获得 ${durationValue("play-value")} 分钟可用时间`;
      pauseButton.innerHTML = '<i data-lucide="shield-alert" aria-hidden="true"></i>申请暂停';
      announcement = `已进入锁定阶段，共 ${durationValue("lock-value")} 分钟`;
    }

    running.querySelector(".running-orbit").innerHTML = `<i data-lucide="${icon}" aria-hidden="true"></i>`;
    if (announce) focusAnnouncement.textContent = announcement;
    refreshIcons();
  }

  function advanceFocusPhase() {
    focusPhase = focusPhase === "lock" ? "play" : "lock";
    const durationId = focusPhase === "lock" ? "lock-value" : "play-value";
    focusRemainingSeconds = durationValue(durationId) * 60;
    renderFocusState(true);
    updateCountdown();
    showToast(focusPhase === "lock" ? "玩机时间结束，重新进入锁定阶段" : "锁定阶段完成，已进入玩机时间");
  }

  function resumeFocus(message = "专注任务已继续") {
    focusPaused = false;
    focusPauseRemainingSeconds = 0;
    renderFocusState(true);
    updateCountdown();
    showToast(message);
  }

  function runFocusTimer() {
    window.clearInterval(focusInterval);
    focusInterval = window.setInterval(() => {
      if (focusPaused) {
        focusPauseRemainingSeconds = Math.max(0, focusPauseRemainingSeconds - 1);
        if (focusPauseRemainingSeconds === 0) {
          resumeFocus("暂停时间结束，专注任务已继续");
        } else {
          updateCountdown();
        }
        return;
      }
      focusRemainingSeconds = Math.max(0, focusRemainingSeconds - 1);
      if (focusRemainingSeconds === 0) {
        advanceFocusPhase();
      } else {
        updateCountdown();
      }
    }, 1000);
  }

  document.querySelector("#start-focus").addEventListener("click", () => {
    const task = document.querySelector("#focus-task").value.trim() || "本次专注";
    runningTitle.textContent = task;
    focusPhase = "lock";
    focusRemainingSeconds = durationValue("lock-value") * 60;
    focusPauseRemainingSeconds = 0;
    focusPaused = false;
    composer.hidden = true;
    running.hidden = false;
    renderFocusState(true);
    updateCountdown();
    runFocusTimer();
    running.focus({ preventScroll: true });
    showToast("专注任务已开始");
  });

  pauseButton.addEventListener("click", () => {
    if (focusPaused) {
      resumeFocus();
      return;
    }
    const dialog = document.querySelector("#pause-dialog");
    if (!dialog.open) dialog.showModal();
  });

  document.querySelector("#pause-form").addEventListener("submit", (event) => {
    event.preventDefault();
    const password = document.querySelector("#pause-password");
    if (!password.value.trim()) {
      password.setCustomValidity("请输入身份验证密码");
      password.reportValidity();
      password.setCustomValidity("");
      return;
    }
    const pauseMinutes = Number(document.querySelector("#pause-duration").value);
    focusPauseRemainingSeconds = pauseMinutes * 60;
    focusPaused = true;
    document.querySelector("#pause-dialog").close();
    password.value = "";
    renderFocusState(true);
    updateCountdown();
    pauseButton.focus({ preventScroll: true });
    showToast(`身份已验证，暂停 ${pauseMinutes} 分钟`);
  });

  document.querySelector("#stop-focus").addEventListener("click", () => {
    window.clearInterval(focusInterval);
    focusPaused = false;
    running.hidden = true;
    composer.hidden = false;
    document.querySelector("#focus-task").focus({ preventScroll: true });
    focusAnnouncement.textContent = "专注任务已结束";
    showToast("专注任务已结束");
  });

  function updateTodayProgress() {
    const tasks = [...document.querySelectorAll("[data-today-task]")];
    const completedTasks = tasks.filter((task) => task.checked).length;
    const habits = [...document.querySelectorAll("[data-habit-toggle]")];
    const completedHabits = habits.filter((habit) => habit.getAttribute("aria-pressed") === "true").length;
    const total = tasks.length + habits.length;
    const completed = completedTasks + completedHabits;
    const percentage = total === 0 ? 0 : Math.round((completed / total) * 100);
    const progress = document.querySelector("#today-progress");
    progress.setAttribute("aria-valuemax", String(total));
    progress.setAttribute("aria-valuenow", String(completed));
    progress.querySelector("span").style.width = `${percentage}%`;
    document.querySelector("#today-progress-label").textContent = `${completed} / ${total}`;
    document.querySelector("#todo-metric").textContent = `${tasks.length - completedTasks} 项`;
    document.querySelector("#habit-metric").textContent = `${completedHabits} / ${habits.length}`;
    document.querySelector("#habit-panel-count").textContent = `${completedHabits} / ${habits.length}`;
  }

  document.querySelectorAll("[data-today-task]").forEach((checkbox) => {
    checkbox.addEventListener("change", updateTodayProgress);
  });

  document.querySelectorAll("[data-habit-toggle]").forEach((button) => {
    button.addEventListener("click", () => {
      const completed = button.getAttribute("aria-pressed") !== "true";
      button.setAttribute("aria-pressed", String(completed));
      button.classList.toggle("is-done", completed);
      const state = button.querySelector(".habit-state");
      state.innerHTML = completed
        ? '<i data-lucide="circle-check" aria-hidden="true"></i>已完成'
        : "打卡";
      refreshIcons();
      updateTodayProgress();
      showToast(completed ? "习惯打卡完成" : "已取消本次打卡");
    });
  });

  document.querySelectorAll(".compact-segmented button").forEach((button) => {
    button.addEventListener("click", () => {
      const group = button.parentElement;
      group.querySelectorAll("button").forEach((item) => {
        const active = item === button;
        item.classList.toggle("is-active", active);
        item.setAttribute("aria-pressed", String(active));
      });
      showToast(`已切换到${button.textContent.trim()}统计`);
    });
  });

  document.querySelectorAll("[data-chart-label]").forEach((button) => {
    button.addEventListener("click", () => {
      document.querySelectorAll("[data-chart-label]").forEach((bar) => {
        const selected = bar === button;
        bar.classList.toggle("is-selected", selected);
        bar.setAttribute("aria-pressed", String(selected));
      });
      document.querySelector("#chart-insight").textContent = button.dataset.chartLabel;
    });
  });

  const dialogs = [...document.querySelectorAll("dialog")];
  document.querySelectorAll("[data-open-dialog]").forEach((button) => {
    button.addEventListener("click", () => {
      const dialog = document.getElementById(button.dataset.openDialog);
      if (dialog && !dialog.open) dialog.showModal();
    });
  });

  document.querySelectorAll("[data-close-dialog]").forEach((button) => {
    button.addEventListener("click", () => button.closest("dialog")?.close());
  });

  dialogs.forEach((dialog) => {
    dialog.addEventListener("click", (event) => {
      if (event.target === dialog) dialog.close();
    });
  });

  document.querySelectorAll("#quick-note-dialog .choice-chip").forEach((button) => {
    button.addEventListener("click", () => {
      document.querySelectorAll("#quick-note-dialog .choice-chip").forEach((choice) => {
        const selected = choice === button;
        choice.classList.toggle("is-selected", selected);
        choice.setAttribute("aria-pressed", String(selected));
      });
    });
  });

  document.querySelector("#quick-note-form").addEventListener("submit", (event) => {
    event.preventDefault();
    const content = document.querySelector("#quick-note-content");
    if (!content.value.trim()) {
      content.setCustomValidity("请输入记录内容");
      content.reportValidity();
      content.setCustomValidity("");
      return;
    }
    document.querySelector("#quick-note-dialog").close();
    const selectedType = document.querySelector('#quick-note-dialog .choice-chip[aria-pressed="true"]')?.dataset.recordType || "闪记";
    content.value = "";
    showToast(`记录已保存到${selectedType}`);
  });

  document.querySelectorAll('input[name="plan-type"]').forEach((radio) => {
    radio.addEventListener("change", () => {
      document.querySelectorAll("[data-plan-fields]").forEach((fields) => {
        fields.hidden = fields.dataset.planFields !== radio.value;
      });
    });
  });

  function createPlanRow(plan) {
    const article = document.createElement("article");
    article.className = "plan-row";

    const icon = document.createElement("div");
    icon.className = `plan-icon ${plan.type === "app" ? "tone-blue" : "tone-green"}`;
    icon.innerHTML = `<i data-lucide="${plan.type === "app" ? "smartphone" : "calendar-clock"}" aria-hidden="true"></i>`;

    const planMain = document.createElement("div");
    planMain.className = "plan-main";
    const titleLine = document.createElement("div");
    titleLine.className = "plan-title-line";
    const title = document.createElement("h3");
    title.textContent = plan.name;
    const state = document.createElement("span");
    state.className = "state-label success";
    state.textContent = "已启用";
    titleLine.append(title, state);
    const schedule = document.createElement("p");
    schedule.textContent = plan.type === "app"
      ? `${plan.targetApp} · ${plan.days} · ${plan.start}–${plan.end}`
      : `${plan.days} · ${plan.start}–${plan.end}`;
    const facts = document.createElement("div");
    facts.className = "plan-facts";
    const primaryFact = document.createElement("span");
    const secondaryFact = document.createElement("span");
    primaryFact.textContent = plan.type === "app"
      ? `每日额度 ${plan.quota} 分钟`
      : `可用 ${plan.play} 分钟`;
    secondaryFact.textContent = plan.type === "app"
      ? `休息 ${plan.rest} 分钟`
      : `锁定 ${plan.lock} 分钟`;
    facts.append(primaryFact, secondaryFact);
    planMain.append(titleLine, schedule, facts);

    const switchLabel = document.createElement("label");
    switchLabel.className = "switch-control";
    const accessibleName = document.createElement("span");
    accessibleName.className = "sr-only";
    accessibleName.textContent = `启用${plan.name}`;
    const input = document.createElement("input");
    input.type = "checkbox";
    input.checked = true;
    input.dataset.planToggle = plan.name;
    input.dataset.activeLabel = "已启用";
    input.dataset.activeTone = "success";
    const track = document.createElement("span");
    track.className = "switch-track";
    track.setAttribute("aria-hidden", "true");
    switchLabel.append(accessibleName, input, track);

    article.append(icon, planMain, switchLabel);
    return article;
  }

  document.querySelector("#plan-form").addEventListener("submit", (event) => {
    event.preventDefault();
    const nameInput = document.querySelector("#plan-name");
    const name = nameInput.value.trim();
    if (!name) {
      nameInput.setCustomValidity("请输入计划名称");
      nameInput.reportValidity();
      nameInput.setCustomValidity("");
      return;
    }
    const type = document.querySelector('input[name="plan-type"]:checked').value;
    const plan = type === "app"
      ? {
          type,
          name,
          targetApp: document.querySelector("#app-plan-target").value,
          days: document.querySelector("#app-plan-days").value,
          start: document.querySelector("#app-plan-start").value || "08:00",
          end: document.querySelector("#app-plan-end").value || "23:00",
          quota: document.querySelector("#app-plan-quota").value || "45",
          rest: document.querySelector("#app-plan-rest").value || "10"
        }
      : {
          type,
          name,
          days: document.querySelector("#global-plan-days").value,
          start: document.querySelector("#global-plan-start").value || "20:30",
          end: document.querySelector("#global-plan-end").value || "22:30",
          play: document.querySelector("#global-plan-play").value || "30",
          lock: document.querySelector("#global-plan-lock").value || "5"
        };
    const listId = type === "app" ? "app-plan-list" : "global-plan-list";
    const list = document.getElementById(listId);
    list.append(createPlanRow(plan));
    const panel = list.closest(".tab-panel");
    const badge = panel.querySelector(".count-badge");
    badge.textContent = `${list.children.length} 项`;
    const tab = document.querySelector(`[data-tab-target="${panel.id}"]`);
    activateTab(tab);
    document.querySelector("#plan-dialog").close();
    updateEnabledPlanCount();
    refreshIcons();
    showToast(`计划“${name}”已创建`);
  });

  const preferredTheme = safeStorageGet("controlfree-prototype-theme") || "dark";
  applyTheme(preferredTheme);
  updateEnabledPlanCount();
  updateTodayProgress();
  refreshIcons();
  showRoute(window.location.hash.slice(1) || "monitor", false);
})();
