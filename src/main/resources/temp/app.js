// /js/app.js
console.log("🚀 app.js loaded");

// Список скриптов дашборда (старые файлы, которые уже работают)
const DASHBOARD_SCRIPTS = [
    "/js/dashboard/strategy-chart.js",
    "/js/dashboard/strategy-controls.js",
    "/js/dashboard/strategy-table.js",
    "/js/dashboard/strategy-init.js",
    "/js/strategy-live-chart.js"
];

function loadScriptOnce(src) {
    return new Promise((resolve, reject) => {
        // уже загружен?
        if (document.querySelector(`script[data-dynamic-src="${src}"]`)) {
            return resolve();
        }

        const s = document.createElement("script");
        s.src = src;
        s.dataset.dynamicSrc = src;
        s.onload = () => resolve();
        s.onerror = (e) => reject(e);
        document.body.appendChild(s);
    });
}

async function ensureDashboardScripts() {
    for (const src of DASHBOARD_SCRIPTS) {
        try {
            await loadScriptOnce(src);
        } catch (e) {
            console.error("Ошибка загрузки скрипта", src, e);
        }
    }
}

function isStrategyDashboardPresent() {
    return !!document.getElementById("strategy-dashboard");
}

/**
 * SPA-навигация: подгружаем HTML дашборда и вставляем в <main>
 */
async function navigateToDashboard(url) {
    const main = document.getElementById("page-main") || document.querySelector("main");
    if (!main) {
        window.location.href = url;
        return;
    }

    try {
        const res = await fetch(url, {
            headers: {
                "X-Requested-With": "XMLHttpRequest"
            }
        });

        if (!res.ok) {
            throw new Error("HTTP " + res.status);
        }

        const html = await res.text();
        const parser = new DOMParser();
        const doc = parser.parseFromString(html, "text/html");
        const section = doc.getElementById("strategy-dashboard");

        // если что-то не так — обычный переход
        if (!section) {
            window.location.href = url;
            return;
        }

        main.innerHTML = "";
        // переносим только сам section
        main.appendChild(section);

        // загружаем скрипты дашборда (если ещё не)
        await ensureDashboardScripts();

    } catch (e) {
        console.error("Ошибка загрузки дашборда", e);
        window.location.href = url;
    }
}

document.addEventListener("DOMContentLoaded", () => {
    console.log("✅ app.js DOMContentLoaded");

    // 1) Если дашборд уже на странице (прямой заход по URL) — подгружаем скрипты
    if (isStrategyDashboardPresent()) {
        ensureDashboardScripts();
    }

    // 2) Перехватываем клики по кнопкам "Дашборд" на списке стратегий
    document.body.addEventListener("click", (e) => {
        const link = e.target.closest(".js-dashboard-link");
        if (!link) return;

        // только ЛКМ + без модификаторов
        if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) {
            return;
        }

        e.preventDefault();
        const url = link.getAttribute("href");
        if (!url) return;

        navigateToDashboard(url);
    });
});
