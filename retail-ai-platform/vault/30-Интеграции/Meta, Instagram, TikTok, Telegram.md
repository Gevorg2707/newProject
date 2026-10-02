---
type: integration
checked: 2026-10-02
method: live fetch через Firecrawl; developers.facebook.com, TikTok и core.telegram.org напрямую из песочницы заблокированы
---
# Meta Ads, Instagram, TikTok, Telegram

## Статус ссылок (2026-10-02)
- Meta Ads Insights — живая (200). Старый путь `/docs/marketing-api/insights` редиректит сюда.
- Meta authorization — живая (200).
- Instagram Insights — 301 → `/documentation/instagram-platform/insights`.
- `business-api.tiktok.com/portal` — живая, но это маркетинговая страница; доки в `/portal/docs/...`.
- `core.telegram.org/api/stats` — прямой fetch заблокирован (UNVERIFIED), по индексу живая, «Channel statistics».
- Мёртвая: help-страница Meta `393403562152731` → 404. Правильная: `facebook.com/business/help/393890194130036`.

## Meta Ads — файловый путь (P0) — FACT
- Ads Manager: **Export as .csv / .xlsx**, Customize export, **Schedule export** (email людям с доступом к аккаунту), Share table link. (help/393890194130036)
- Не экспортируются: Tags, Age, Gender, Location, Body, Destination Type, Link, Preview link, Related Page, Title.
- ASSUMPTION: колонки = колонки таблицы (Spend, Impressions, Clicks, Results/Purchases, Reporting starts/ends). Точные заголовки снять с реального экспорта.
- UNVERIFIED: периодичность расписания (daily/weekly).
- **Готово для MVP.**

## Meta Ads — API (P1/P2) — FACT
- Insights требует `ads_read`. Примеры на `v26.0`. Graph API v26.0 выпущен 2026-07-29; Marketing API v24.0 доступна до 2026-10-06 (жизнь версии ≈12 мес.) → закладывать апгрейд версии минимум раз в год.
- Уровни доступа переименованы: **Marketing API Access Tier**: *Limited* (по умолчанию, «heavily rate-limited, for development only», 1 system user) и *Full* (после App Review, ≥500 вызовов за 15 дней, <15% ошибок на последних 500, до 10 system users).
- Rate limit (BUC): Standard `600 + 400×active ads − 0.001×user errors` вызовов/час; Advanced `190000 + 400×active ads − …`.
- **Insights обновляются каждые 15 мин и не меняются после 28 дней** → ads-данные последних 28 дней считать нефинальными; перетягивать окно 28 дней. Async: `POST <object>/insights` → report_run_id, живёт 30 дней.
- `date_preset=maximum` ≈ 37 месяцев истории (из старой версии страницы; на текущей UNVERIFIED).
- Для SaaS с чужими аккаунтами нужен App Review и Full tier. CONDITIONAL.

## Instagram Insights — FACT
- Только **professional account** (Business/Creator). Два пути: Business Login for Instagram (`instagram_business_basic`, `instagram_business_manage_insights`) или Facebook Login for Business (`instagram_basic`, `instagram_manage_insights`, `pages_read_engagement`).
- **User metrics хранятся до 90 дней** → без непрерывного сбора история теряется.
- Rate limit: 4800 × impressions за 24 ч на app-user.
- Официальный CSV-экспорт инсайтов **не найден** (UNVERIFIED) → в MVP ручной ввод с пометкой «введено вручную», как и предусмотрено документом.

## TikTok — FACT
- Ads Manager: Analytics → Custom reports → **Scheduled running**, до 5 получателей, автоотмена через 3 мес. без скачивания, доставка ~5–6 утра по TZ аккаунта. Формат CSV/XLSX — UNVERIFIED (только сторонние источники). **Файловый MVP готов.**
- Business API v1.3: приложение → scope (Ad Account Management, Ads Management, **Reporting**, App Management) → ревью 2–3 рабочих дня. Sandbox на каждое приложение, отдельный base URL.
- Лимиты: Basic 10 QPS / 600 QPM / 864k QPD; Advanced 20; Premium 30; Ultimate 50. Async reports `/report/task/create/`: 2 QPS / 60 QPM / 4500 QPD на всех тирах.
- Display API (органика, developers.tiktok.com) — другой продукт: `user.info.basic`, `user.info.stats`, `video.list`, только счётчики, нужен app review. UNVERIFIED (по индексу).

## Telegram — FACT / UNVERIFIED
- MTProto `stats.getBroadcastStats`: только админы каналов «определённого размера» (порог серверный, флаг `can_view_stats` в channelFull), надо ходить в `stats_dc`; данные: подписчики, просмотры/шеры по постам, графики роста/источников через `stats.loadAsyncGraph`. **Только пользовательская сессия, не бот** (CHAT_ADMIN_REQUIRED).
- Bot API статистики не даёт (только `getChatMemberCount`) — UNVERIFIED, страница заблокирована.
- Экспорт stats из Telegram Desktop в CSV — заявляет один сторонний блог, другие отрицают → UNVERIFIED. **Файловый путь слабый**: ручной ввод 3–5 показателей.
- Хранить пользовательскую MTProto-сессию клиента в SaaS = риск безопасности; оставить P3.

## Готовность
| Платформа | Файловый MVP | API |
|---|---|---|
| Meta Ads | **Готово** (CSV/XLSX + расписание) | Готово для своих аккаунтов; для чужих — App Review + Full tier |
| Instagram | Нет экспорта → ручной ввод | Готово, 90-дневное окно → непрерывный сбор |
| TikTok Ads | **Готово** (scheduled reports) | Ревью 2–3 дня, sandbox есть |
| Telegram | Слабо (ручной ввод) | MTProto user-session, порог размера канала; P3 |

Порядок фазы API: Meta → TikTok → Instagram → Telegram последним.
