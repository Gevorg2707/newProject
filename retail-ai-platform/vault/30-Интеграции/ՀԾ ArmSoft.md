---
type: integration
checked: 2026-10-02
method: live fetch (Firecrawl, maxAge 0); api.armsoft.am Swagger недоступен из песочницы
---
# ՀԾ (ArmSoft): ՀԾ-Առևտուր 9 и ՀԾ-Հաշվապահ 9

## Статус ссылок (все живые, HTTP 200, 2026-10-02)
- https://online.armsoft.am/trade7/restapi_salesanalysis.htm
- https://online.armsoft.am/trade7/restapi_productsbalancesshort.htm
- https://online.armsoft.am/trade7/restapi_secretkeygen.htm
- https://online.armsoft.am/acc7/rest_api_reports.htm
- https://online.armsoft.am/acc7/rest_api_online_store_box.htm
- https://online.armsoft.am/acc7/importdatafromtrade9.htm
- Индексы: trade7/restapi.htm, acc7/rest_api.htm, trade7/restapi_tariff_restrictions.htm, trade7/restapi_pagination.htm
- GitHub: `armsoft/trade-public-api-docs` (автосинк из Swagger, FACT)

## Файловый путь (P0) — FACT
- Любой список/грид в ՀԾ-Առևտուր экспортируется в **XLS/XLSX**: Գլխավոր → Տվյալներ → Արտահանել Excel. Экспортируются все отображённые строки, фильтры сужают выборку. (trade7/ui_grid_exportexcel.htm)
- ՀԾ-Հաշվապահ: печатные формы экспортируются в XLS/XLSX с опциями (один файл, диапазон страниц, режим Value/Text, raw data). (acc7/ui_printpreview_xls.htm)
- Отчёты с Excel-экспортом также экспортируются в PDF (whatsnew).
- **CSV-экспорт не найден** — UNVERIFIED/отсутствует. Для MVP принимать XLSX.
- **Нет документированной стабильной схемы экспорта**: колонки надо снимать с реального файла клиента; заголовки на армянском. (риск #2 в [[Топ-10 рисков]])

## Read-only API (P1) — FACT
- Auth: заголовок `apiKey`; ключ наследует права пользователя; `Accept-Language: hy-AM|en-US|ru-RU`; даты ISO 8601. Ключи: до 10, не истекают, создаёт только администратор, показываются один раз.
- Типы ключей: **Public API** (внешние системы: интернет-магазины, CRM) и **ՀԾ Համակարգերի համակցման API** (обмен между продуктами ՀԾ). Для нас — Public API.
- Read-only: явного флага нет; достигается через read-only пользователя (ASSUMPTION, следует из правила наследования прав).
- `POST https://api.armsoft.am/trade/v1/reports/salesanalysis`: `startDate/endDate` обязательны; фильтры по складам, товарам, группам, партнёрам; флаги сумм с/без НДС; `pageSize`. Строки: documentNumber, date, isn, **operationType** (Հաշիվ-ապրանքագիր / Վերադարձ գնորդից / Վաճառք (Կտրոն) / Վաճառք (Մանրածախ)), itemCode, itemName, itemGroup, unit, storage, customer, quantity, cost/sale/profit/discount с/без НДС и в валюте, seller, партия. Возвраты — строки с operationType «Վերադարձ գնորդից»; знак quantity — UNVERIFIED.
- `POST /trade/v1/reports/productsbalances/short`: `date` обязателен; storages, group, codes, priceType, showZeroRows; строки: code, name, unit, quantity, salePrice, costAmount с/без НДС. Есть расширенный `/reports/productsbalances`.
- Пагинация: минимум 5000 строк/страница (меньшее значение поднимается до 5000), `pageSize=0` = всё; ответ `{id, hasMore, data[]}`; след. страница `POST .../nextpage {id, close}`.
- Лимиты: **45 запросов / 3 с на пользователя, 3 параллельных**, 8 KB заголовки, 20 MB запрос, 1000 элементов массива; **автоблокировка API при 200 одинаковых неуспешных запросах/мин за час**. 429 Too Many Requests упоминается.
- Обратная совместимость: существующие поля не меняются, новые могут добавляться.

## Тарифные каналы — FACT / CONDITIONAL
- Два канала: **канал интернет-магазина** и **полный канал интеграции внешних программ**.
- Канал интернет-магазина для Trade включает: справочники (GET), документы Վաճառք/Վերադարձ (Կտրոն) CRUD, журнал ККМ, отчёты Գնացուցակ, Ապրանքների մնացորդներ (+extended), Բոնուսների մնացորդներ.
- **Sales analysis НЕ входит в канал интернет-магазина** → нужен полный канал (FACT по отсутствию в списке). Цены и названия пакетов **не опубликованы** → спрашивать у ArmSoft/клиента. CONDITIONAL.

## ՀԾ-Հաշվապահ 9 — FACT
- REST-отчёты: остатки по счетам, оборот, журнал операций, остатки/журнал контрагентов, справка о наличии МЦ, журнал движений МЦ, журнал ОС и полученных услуг, расчётные данные; журналы документов, выставленных счетов (продажи и возвраты), чеков ККМ. База `https://api.armsoft.am/accountant/...` (точный путь UNVERIFIED).
- В канале интернет-магазина для Accountant: GET по справочникам/документам, POST списков/журналов и **все перечисленные отчёты** с пагинацией. Числовых лимитов и цен на странице нет.
- Связка Trade→Accountant делается ключом типа «ՀԾ Համակարգերի համակցման API». Это внутренняя интеграция, **не даёт прав нашему SaaS** (подтверждает тезис документа).

## Неизвестно / UNVERIFIED
- Цены тарифных каналов. Multi-company на один ключ (ASSUMPTION: ключ на одну базу/компанию). Партнёрская программа — не найдена. Swagger `api.armsoft.am/trade/swagger` недоступен из песочницы.

## Что это значит для проекта
1. **MVP**: XLSX из гридов продаж/возвратов/остатков + печатных форм. Первое действие — получить реальные файлы у 1–2 клиентов и зафиксировать схемы колонок.
2. **Фаза API**: технически зрелый read-only путь с хорошей документацией. Главный gate — тариф «полный канал» у клиента. Балансы и прайс доступны даже в дешёвом канале.
3. Дизайн клиента API: уважать 45/3с и 3 параллельных, экспоненциальный backoff по 429, **никогда не ретраить одинаковый неуспешный запрос в цикле** (риск автоблокировки клиента).
