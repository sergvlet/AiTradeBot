"use strict";

console.log("📊 strategies.js loaded");

document.addEventListener("DOMContentLoaded", () => {

    const buttons = document.querySelectorAll(".toggle-btn");
    if (!buttons.length) {
        console.log("⚠ toggle-btn не найдены на странице стратегий");
        return;
    }

    buttons.forEach((btn) => {
        btn.addEventListener("click", async (e) => {
            e.preventDefault();

            const chatId = btn.dataset.chatId;
            const type = btn.dataset.type;
            const symbol = btn.dataset.symbol;
            const active = btn.dataset.active === "true";

            const card = btn.closest(".card");
            const badge = card ? card.querySelector(".badge") : null;
            const icon = btn.querySelector("i");
            const label = btn.querySelector("span");

            btn.disabled = true;

            // временная анимация состояния
            label.textContent = active ? "Останавливаем..." : "Запускаем...";
            icon.className = "bi bi-hourglass-split";

            try {
                const response = await fetch(
                    `/api/strategy/toggle?chatId=${chatId}&type=${type}&symbol=${symbol}`,
                    { method: "POST" }
                );

                const data = await response.json();

                if (!response.ok || data.success === false) {
                    throw new Error(data.message || "Ошибка переключения");
                }

                // новый статус с бэка
                const started = data.active === true;
                btn.dataset.active = String(started);

                // кнопка
                btn.classList.toggle("btn-outline-success", !started);
                btn.classList.toggle("btn-outline-danger", started);
                icon.className = started ? "bi bi-stop-fill" : "bi bi-play-fill";
                label.textContent = started ? "Остановить" : "Запустить";

                // бордер карточки
                if (card) {
                    card.classList.toggle("border-success", started);
                    card.classList.toggle("border-2", started);
                }

                // бейдж
                if (badge) {
                    badge.textContent = started ? "🟢 Активна" : "⚫ Остановлена";
                    badge.classList.toggle("bg-success", started);
                    badge.classList.toggle("bg-secondary", !started);
                }

                if (window.showToast) {
                    const msg = data.message || (started ? "Стратегия запущена" : "Стратегия остановлена");
                    showToast(msg, started);
                }

                if (data.redirect) {
                    window.location.href = data.redirect;
                }

            } catch (err) {
                console.error("Ошибка переключения стратегии", err);
                if (window.showToast) {
                    showToast("Ошибка переключения стратегии", false);
                }
            } finally {
                btn.disabled = false;
            }
        });
    });
});
