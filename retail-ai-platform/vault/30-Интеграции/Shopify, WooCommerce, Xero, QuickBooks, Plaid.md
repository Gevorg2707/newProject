---
type: integration
checked: 2026-10-02
method: live fetch через Firecrawl (WebFetch заблокирован); часть вторичных страниц не достигнута из-за rate limit
---
# Международные источники (фазы 3–4): Shopify, WooCommerce, Xero, QuickBooks, Plaid

## Статус ссылок (2026-10-02)
- shopify.dev/docs/apps/build/apis — живая.
- woocommerce.github.io/woocommerce-rest-api-docs — **редирект** на https://developer.woocommerce.com/docs/apis/rest-api/ → обновить ссылку в документе.
- developer.xero.com banktransactions — живая.
- developer.intuit.com invoice — живая (с первого раза упала на прокси).
- support.plaid.com страны — живая.

## Shopify — FACT
- Версии квартальные; актуальная стабильная **2026-10** (01.10.2026), 2027-01 — RC; поддержка версии ≥12 мес.
- **REST Admin API — legacy; новые приложения на GraphQL Admin API.**
- Rate limit GraphQL: cost-based leaky bucket; Standard 100 pts/s, Advanced 200, Plus 1000, Enterprise 2000; запрос ≤1000 pts; мутация 10; bulk operations вне лимитов. Массивы ≤250, пагинация до 25 000 объектов.
- **Заказы: по умолчанию только последние 60 дней**; старше — scope `read_all_orders`, выдаётся при обоснованной необходимости. Для нашего 90-дневного онбординга это gate.
- Webhooks — основной механизм изменений (ORDERS_CREATE и др.), версия в заголовке.
- CSV-экспорт заказов/товаров из админки — UNVERIFIED (help.shopify.com не достигнут). ASSUMPTION: есть.

## WooCommerce — FACT
- Текущая версия `wc/v3`; WC 3.5+, WP 4.4+, pretty permalinks; ключи в WooCommerce → Settings → Advanced → REST API; auth — HTTP Basic с consumer key/secret; endpoints `wp-json/wc/v3/orders`, `products`; пагинация `per_page/page/offset`, заголовки `X-WP-Total`.
- UNVERIFIED: отдельный stock-endpoint (остаток — поле товара/вариации, ASSUMPTION); встроенный CSV-экспорт заказов (ASSUMPTION: товары — ядро, заказы — расширение).

## Xero — FACT
- `GET/PUT/POST api.xero.com/api.xro/2.0/BankTransactions`: spend/receive money, предоплаты; **не включает банковские выписки/фиды и платежи по счетам**; paging до 250, If-Modified-Since; >100k строк → 400.
- Лимиты: 5 параллельных/tenant, 60/мин, 1 000/день (Starter) или 5 000/день (Core+); 10 000/мин на приложение; 429 + Retry-After.
- **Тарифы с 02.03.2026**: Starter бесплатно (5 подключений), Core 35 AUD/мес (50), Plus 245 AUD/мес (1 000, нужна сертификация), Advanced 1 445 AUD/мес; данные API нельзя использовать для обучения ML.
- Bank Feeds API — только для финансовых институтов-партнёров Xero. Страны фидов UNVERIFIED.
- Вывод: для первых зарубежных клиентов Starter хватает; масштабирование платное.

## QuickBooks Online — FACT / UNVERIFIED
- Invoice: CRUD, query, void, send, PDF; `/v3/company/<realmID>/invoice`; sandbox `sandbox-quickbooks.api.intuit.com`; OAuth 2.0 Bearer; minor versions.
- App Partner Program — только партнёры из US, UK, Australia, Canada (кроме Квебека). Для армянской компании это gate на листинг (ASSUMPTION: приватное приложение возможно).
- UNVERIFIED: rate limits, срок жизни токенов, процесс ревью, полный список стран.

## Plaid — FACT
- Поддержка: США, Канада, UK и ~17 стран Европы. **Армении нет.** Только Identity Verification глобальна. Банковские данные Армении через Plaid невозможны.
- Альтернативные агрегаторы для Армении — UNVERIFIED (официальных не найдено).

## Готовность
| Источник | Статус | Главный gate |
|---|---|---|
| Shopify | Готово (GraphQL 2026-10) | 60-дневное окно заказов без `read_all_orders` |
| WooCommerce | Готово (`wc/v3`) | Качество хостинга клиента, CSV UNVERIFIED |
| Xero | Готово для ≤5 подключений | Платные тиры от 50 подключений |
| QuickBooks | Частично проверено | Партнёрская программа только US/UK/AU/CA |
| Plaid | Не применимо к Армении | — |
