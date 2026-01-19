/* ============================================================================
   AITRADEBOT UI V4 — CORE JS SHELL (ChatGPT-style)
   ============================================================================ */

"use strict";

console.log("🚀 app.js loaded (AITradeBot UI V4)");

document.addEventListener("DOMContentLoaded", () => {
    initBootstrapTooltips();
    initSidebarToggle();
    initThemeToggle();
});

/* ============================================================================
   01. BOOTSTRAP TOOLTIPS
   ============================================================================ */

function initBootstrapTooltips() {
    if (typeof bootstrap === "undefined") {
        console.warn("⚠ Bootstrap не найден — тултипы отключены");
        return;
    }

    const elements = document.querySelectorAll('[data-bs-toggle="tooltip"]');
    elements.forEach((el) => new bootstrap.Tooltip(el));

    console.log(`✔ Tooltips инициализированы (${elements.length})`);
}

/* ============================================================================
   02. SIDEBAR (Desktop + Mobile)
   ============================================================================ */

function initSidebarToggle() {
    const body = document.body;

    const collapseBtn = document.getElementById("sidebarCollapse");
    const mobileBtn = document.getElementById("sidebarToggle");
    const handle = document.getElementById("sidebarHandle");
    const overlay = document.getElementById("sidebarOverlay");

    if (collapseBtn) {
        collapseBtn.addEventListener("click", () => {
            body.classList.toggle("sidebar-collapsed");
        });
    }

    const openSidebar = () => body.classList.add("sidebar-open");
    const closeSidebar = () => body.classList.remove("sidebar-open");

    if (mobileBtn) mobileBtn.addEventListener("click", openSidebar);
    if (handle) handle.addEventListener("click", openSidebar);
    if (overlay) overlay.addEventListener("click", closeSidebar);

    console.log("✔ Sidebar toggle system initialized");
}

/* ============================================================================
   03. THEME SWITCH (Light / Dark)
   ============================================================================ */

function initThemeToggle() {
    const btn = document.getElementById("themeToggle");
    const icon = document.getElementById("themeToggleIcon");

    if (!btn || !icon) {
        console.warn("⚠ themeToggle / themeToggleIcon не найдены");
        return;
    }

    const applyTheme = (theme) => {
        const body = document.body;

        if (theme !== "light" && theme !== "dark") {
            theme = "dark";
        }

        body.setAttribute("data-theme", theme);

        if (theme === "light") {
            icon.classList.remove("bi-moon-stars");
            icon.classList.add("bi-sun");
        } else {
            icon.classList.remove("bi-sun");
            icon.classList.add("bi-moon-stars");
        }

        console.log("🎨 Theme applied:", theme);
    };

    const saved = window.localStorage.getItem("aitrade_theme") || "dark";
    applyTheme(saved);

    btn.addEventListener("click", () => {
        const current = document.body.getAttribute("data-theme") || "dark";
        const next = current === "light" ? "dark" : "light";
        window.localStorage.setItem("aitrade_theme", next);
        applyTheme(next);
    });
}
