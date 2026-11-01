# Lifety — контракты интеграции v1 (Core & Planner P0)

## Статус и структура

Core & Planner P0 реализован в ветке `feature/core`, worktree
`/Users/rugilf/Projects/lifety-core`. Основной репозиторий:
`/Users/rugilf/Projects/HSE-hack-2026`. Эти сведения описывают первоначальный
этап: текущая рабочая ветка теперь `main` в `/Users/rugilf/Projects/lifety-core`;
снимок до iOS находится в `HSE-hack-2026` (`f8c942e`). Старые feature/codex
ветки удалены по последующему запросу владельца, рабочие каталоги сохранены.
Реализованы SQLite-репозитории всех трёх доменов, задачи, календарь, DI и навигация.
Finance интегрирован до `origin/feature/finance` (`c703a1f`): его экран использует
общий expenses repository. Dashboard, Assistant и Health подключены из
`origin/feature/assistant` (`e30a688`). Их публичные screen signatures сохранены.
В iOS-адаптерах исправлена совместимость NSDate factory. Локальная альтернативная
`feature/assistant` (`e63b63c`) не объединялась: это другая mock-AI/DI реализация.
Два Gradle-модуля: `:androidApp` (запуск Android) и `:shared` (KMP, включая Compose).
Границы функций — каталоги и Kotlin-пакеты внутри `:shared`, не отдельные
Gradle-модули. Это сокращает настройку и число общих файлов при параллельной работе.

Базовый пакет: `ru.lifety`. Далее `C` означает
`shared/src/commonMain/kotlin/ru/lifety`, `A` —
`shared/src/androidMain/kotlin/ru/lifety`, `I` —
`shared/src/iosMain/kotlin/ru/lifety`.

| Файлы | Пакет | Владелец |
| --- | --- | --- |
| `C/core/model/{Task,Expense,HealthSnapshot}.kt` | `ru.lifety.core.model` | Codex 1 |
| `C/core/repository/{TaskRepository,ExpenseRepository,HealthRepository,Repositories}.kt` | `ru.lifety.core.repository` | Codex 1 |
| `C/core/ui/{LocalRepositories,PlaceholderScreen}.kt` | `ru.lifety.core.ui` | Codex 1 |
| `C/core/network/HttpClientFactory.kt`, `A/core/network/HttpClientFactory.android.kt`, `I/core/network/HttpClientFactory.ios.kt` | `ru.lifety.core.network` | Codex 1 |
| `C/database/DatabaseDriverFactory.kt`, `A/database/DatabaseDriverFactory.android.kt`, `I/database/DatabaseDriverFactory.ios.kt` | `ru.lifety.database` | Codex 1 |
| `shared/src/commonMain/sqldelight/ru/lifety/database/Lifety.sq` | generated: `ru.lifety.database.generated` | Codex 1 |
| `C/app/LifetyApp.kt`, `C/app/navigation/{AppDestination,RootNavigator}.kt`, `I/app/MainViewController.kt` | `ru.lifety.app`, `ru.lifety.app.navigation` | Codex 1 |
| `C/feature/tasks/**` | `ru.lifety.feature.tasks` | Codex 1 |
| `C/feature/calendar/**` | `ru.lifety.feature.calendar` | Codex 1 |
| `C/feature/finance/**` | `ru.lifety.feature.finance` | Codex 2 |
| `A/platform/notifications/**` | `ru.lifety.platform.notifications` | Codex 2 |
| `C/feature/assistant/**`, включая `ai/**` | `ru.lifety.feature.assistant`, `ru.lifety.feature.assistant.ai` | Codex 3 |
| `C/feature/dashboard/**` | `ru.lifety.feature.dashboard` | Codex 3 |
| `C/feature/health/**` | `ru.lifety.feature.health` | Codex 3 |
| `androidApp/**`, все Gradle-файлы, wrapper, Manifest, README, CONTRACTS, AGENTS | `ru.lifety.android` для Android entry point | Codex 1 |

Платформенные адаптеры функций можно добавлять в `A/feature/<feature>/**` и
`I/feature/<feature>/**` с тем же владельцем. Тесты живут в `shared/src/commonTest/`,
`shared/src/androidHostTest/`, `shared/src/iosTest/` с зеркальной структурой пакетов;
владелец теста совпадает с владельцем кода. Общие тесты моделей, БД и навигации — Codex 1.

## Единые модели

Все три — `@Serializable data class`, все свойства `val`, без значений по умолчанию.
Имена полей Kotlin и JSON совпадают; переносить модели в feature-пакеты запрещено.

```kotlin
package ru.lifety.core.model

@Serializable
data class Task(
    val id: String,
    val title: String,
    val scheduledAt: Long?,
    val deadlineAt: Long?,
    val isCompleted: Boolean,
)

@Serializable
data class Expense(
    val id: String,
    val amountMinor: Long,
    val currency: String,
    val merchant: String,
    val category: String,
    val timestamp: Long,
    val source: String,
)

@Serializable
data class HealthSnapshot(
    val timestamp: Long,
    val sleepMinutes: Int?,
    val steps: Int?,
)
```

- Все даты — Unix epoch в **миллисекундах UTC**. Часовой пояс применяется в UI.
- `null` времени означает отсутствие даты; `null` health-показателя — неизвестное
  значение, а не ноль. `sleepMinutes` — целые минуты, `steps` — целое число шагов.
- `id` — стабильная непрозрачная строка, создаётся вызывающим кодом; репозиторий
  не заменяет id. Формат UUID допустим, но интерфейс его не требует.
- `amountMinor` — целое количество минимальных единиц валюты; 12345 RUB = 123,45 ₽.
  Не переводить деньги в `Double`. `currency` — uppercase ISO 4217, например `RUB`.
- `category`, `source` — строки, а не общие enum. Для будущего ручного ввода
  согласованы `source = "manual"` и резервная категория `"other"`.
- Здесь нет валидации, правил категоризации, расчётов или получения health-данных.

## Репозитории

Файлы и пакет указаны в таблице. `Flow` — `kotlinx.coroutines.flow.Flow`.
Изменяющие методы **suspend**, возвращают `Unit`; сигнатуры не меняются агентами функций.

```kotlin
interface TaskRepository {
    fun observeAll(): Flow<List<Task>>
    suspend fun upsert(task: Task)
    suspend fun delete(id: String)
}

interface ExpenseRepository {
    fun observeAll(): Flow<List<Expense>>
    suspend fun upsert(expense: Expense)
    suspend fun delete(id: String)
}

interface HealthRepository {
    fun observeLatest(): Flow<HealthSnapshot?>
    suspend fun update(snapshot: HealthSnapshot)
    fun observeAll(): Flow<List<HealthSnapshot>>
    suspend fun updateAll(snapshots: List<HealthSnapshot>)
}

data class Repositories(
    val tasks: TaskRepository,
    val expenses: ExpenseRepository,
    val health: HealthRepository,
)
```

Гарантии SQLDelight-реализаций Codex 1:

- Наблюдение выдаёт текущее состояние при подписке и изменения после успешной
  записи. Для пустой БД — `emptyList()` и `null`, без фиктивных данных.
- `upsert` полностью заменяет запись с тем же id либо вставляет новую;
  `delete` отсутствующего id успешен и ничего не меняет.
- Детерминированный порядок задач: `id ASC`; расходов: `timestamp DESC, id ASC`.
  UI задач/календаря сам выбирает необходимое представление и сортировку.
- Health хранит snapshots по timestamp; `update` заменяет snapshot с тем же
  timestamp. `observeLatest` выбирает максимальный timestamp, не последнюю запись
  по моменту вызова. `null`-показатели заменяются, а не сливаются со старым snapshot.
- `health.observeAll()` в SQLDelight выдаёт всю сохранённую историю в порядке
  `timestamp DESC`; `updateAll()` выполняет пакет в одной транзакции (всё или ничего).
  Для исходной совместимости интерфейс по умолчанию преобразует `observeLatest()`
  в список и вызывает `update()` для каждого элемента пакета. Адаптеры с собственной
  историей обязаны переопределить оба метода, как production SQLDelight.
  Атомарность пакета гарантируется SQLDelight, а не default-адаптером.
- Запись атомарна. Дисковые операции не блокируют UI; ошибки доходят до вызывающего
  кода, `CancellationException` не поглощается. Ошибки не маскируются пустыми списками.
- Репозитории создаются один раз в composition root, общие для всех функций.
  UI-state функций публикуется как `StateFlow`, изменяемый flow остаётся приватным.

Реализации: `ru.lifety.database.SqlDelightTaskRepository`,
`SqlDelightExpenseRepository`, `SqlDelightHealthRepository` в
`C/database/SqlDelightRepositories.kt`. Запросы выполняются на переданном
`CoroutineDispatcher` (по умолчанию `Dispatchers.IO`); SQLDelight `asFlow()` с
`mapToList`/`mapToOneOrNull` передаёт изменения всем подписчикам. Одна SQL-команда
`INSERT OR REPLACE` атомарно полностью заменяет строку. Feature-local test fake
допустим только в тестах; production использует одну SQLite БД.

## SQLite и DI

Единственный стек БД — SQLDelight **2.4.0**: AndroidSqliteDriver на Android,
NativeSqliteDriver на iOS. `JdbcSqliteDriver` используется только в host-тесте.
Имя файла БД — `lifety.db`, сгенерированный класс —
`ru.lifety.database.generated.LifetyDatabase`.
Схема содержит таблицы `task`, `expense`, `healthSnapshot` с полями моделей;
`Boolean` и nullable `Int` имеют SQLDelight type projections.
CRUD-запросы находятся в существующем `Lifety.sq`: `selectTasks`, `upsertTask`,
`deleteTask`, `selectExpenses`, `upsertExpense`, `deleteExpense`,
`selectLatestHealth`, `selectHealthHistory`, `upsertHealth`. Формат таблиц и schema version 1 не изменены,
миграция для этого этапа не требуется. Int-адаптеры подключаются в
`createDatabase`, проверяют диапазон Int при чтении SQLite INTEGER.
Сгенерированный код в `build/` не редактировать и не коммитить; дальнейшие
изменения схемы и миграции согласует Codex 1.

Стабильные фабрики:

```kotlin
// commonMain, ru.lifety.database
expect class DatabaseDriverFactory {
    fun createDriver(): SqlDriver
}
fun createDatabase(driver: SqlDriver): LifetyDatabase
// androidMain constructor: DatabaseDriverFactory(context: Context)
// iosMain constructor: DatabaseDriverFactory()
```

`ru.lifety.core.di.AppContainer(driver: SqlDriver, dispatcher: CoroutineDispatcher = Dispatchers.IO)`
в `C/core/di/AppContainer.kt` удерживает driver и один `Repositories` для приложения;
`close()` закрывает соединение. Репозитории не закрываются при переходах между экранами.

Android: `ru.lifety.android.LifetyApplication` в
`androidApp/src/main/kotlin/ru/lifety/android/LifetyApplication.kt` владеет
контейнером на время процесса. `container(): Deferred<AppContainer>` создаёт его
в application coroutine scope на IO, сохраняет один успешный результат и
позволяет повторить инициализацию после ошибки. MainActivity ожидает контейнер
через `RepositoryBootstrap`, передаёт `repositories` в `LifetyApp`. Смена Activity
не создаёт новую БД. `android:name=".LifetyApplication"` установлен в Manifest.
На iOS `MainViewController` использует один lazy-контейнер на время процесса,
также создаваемый вне UI-потока. ОС освобождает ресурсы при завершении процесса;
тесты и явно созданные контейнеры вызывают `close()` самостоятельно.

`RepositoryBootstrap` показывает загрузку/ошибку и повторную попытку без подмены
БД in-memory данными. UI всех функций получает тот же набор через
`ru.lifety.core.ui.LocalRepositories.current` с полями `.tasks`, `.expenses`, `.health`.
`LifetyApp(repositories: Repositories? = null)` сохраняет прежнюю сигнатуру:
`null` допустим для изолированного preview/test host; production Android/iOS
entry points передают реальные репозитории. Tasks и Calendar при `null` показывают
явное состояние «хранилище не подключено». Другим экранам следует делать так же.
Finance notification service не обращается к БД: он публикует неподтверждённые
предложения в `FinanceNotifications.inbox`; запись выполняет FinanceScreen
через существующий repository только после подтверждения. Отдельный DI-адаптер
сервису не нужен, отдельный Android process запрещён (очередь общая в памяти).

## Общий визуальный контракт

Все экраны используют корневую `ru.lifety.core.ui.LifetyTheme`. Палитра, размеры,
типографика, правила маскота и точные API общих UI-компонентов закреплены в
[DESIGN_SYSTEM.md](DESIGN_SYSTEM.md). Владелец `C/core/ui/**` — Codex 1.
`LifetyMascot(modifier = Modifier, emotion = LifetyEmotion.Happy, animated = true)`
доступен всем функциям без platform assets. Не дублировать цвета и drawing.
Модели, репозитории, схема БД и feature entry signatures при редизайне не меняются.
Android polish v2 использует стандартную M3 Typography, единые motion specs из
`C/core/ui/LifetyMotion.kt`, компактные секции и сохранение черновиков при
configuration change. Разрешения AI и процессное хранение ключа не менялись.

## Навигационные интеграционные точки

`ru.lifety.app.LifetyApp(repositories: Repositories? = null)` — общий Compose root.
Android launcher: `ru.lifety.android.MainActivity` в
`androidApp/src/main/kotlin/ru/lifety/android/MainActivity.kt`.
iOS entry: `ru.lifety.app.MainViewController()` в `I/app/MainViewController.kt`.
Объявлены targets `android`, `iosArm64`, `iosSimulatorArm64`; framework `LifetyShared`
статический. Xcode host теперь находится в `iosApp/Lifety.xcodeproj`, scheme
`Lifety`, iOS 26.5+, arm64 device/simulator. Build Phase использует
`:shared:embedAndSignAppleFrameworkForXcode`; Swift host перед созданием Compose
controller подключает `IosServices.credentials` (Keychain) и `.health` (HealthKit).
Подробности локального порта — `docs/IOS_PORT.md`.

Каждый публичный вход функции — `@Composable fun XxxScreen(modifier: Modifier = Modifier)`:

| Route / enum | Пакет | Файл и функция |
| --- | --- | --- |
| `dashboard` / `DASHBOARD` | `ru.lifety.feature.dashboard` | `C/feature/dashboard/DashboardScreen.kt`: `DashboardScreen` |
| `tasks` / `TASKS` | `ru.lifety.feature.tasks` | `C/feature/tasks/TasksScreen.kt`: `TasksScreen` |
| `calendar` / `CALENDAR` | `ru.lifety.feature.calendar` | `C/feature/calendar/CalendarScreen.kt`: `CalendarScreen` |
| `finance` / `FINANCE` | `ru.lifety.feature.finance` | `C/feature/finance/FinanceScreen.kt`: `FinanceScreen` |
| `assistant` / `ASSISTANT` | `ru.lifety.feature.assistant` | `C/feature/assistant/AssistantScreen.kt`: `AssistantScreen` |
| `health` / `HEALTH` | `ru.lifety.feature.health` | `C/feature/health/HealthScreen.kt`: `HealthScreen` |

Корень использует Compose Navigation: `rememberNavController()` + `NavHost`.
Шесть прежних строковых routes сохранены. По умолчанию пунктов NavigationBar пять (настраивается 1–5; см. обновление 2026-10-30):
**Главная, План, Финансы, Здоровье, Ассистент**. `tasks` открывает `PlanScreen`
с вкладками Задачи/Календарь; `calendar` — совместимый вход в План с выбранным
календарём. Оба отмечают один пункт «План». Состояния вкладок сохраняются.
`TasksScreen` и `CalendarScreen` сохраняют свои публичные сигнатуры.
Настройки открываются через кнопку верхней панели и `LifetySettingsSheet`.
Начальный раздел — dashboard;
верхние разделы сохраняют состояние при переключении, повторный выбор не создаёт
дубликат destination. Android Back и iOS Back обрабатывает Navigation.

`RootNavigator.destination: StateFlow<AppDestination>` и
`navigate(destination: AppDestination): Unit` сохранены. Это фасад NavHost;
`LocalRootNavigator.current` в пакете `ru.lifety.app.navigation` позволяет
перейти к верхнему разделу, например `navigate(AppDestination.CALENDAR)`.
Конструктор получил необязательный callback `onNavigate`; старый вызов
`RootNavigator(initialDestination)` остаётся корректным. `AppDestination.fromRoute`
сохраняет fallback неизвестной строки в dashboard. Восстановлением реального
back stack и аргументов теперь управляет NavController.

Новый внутренний typed destination:

```kotlin
// ru.lifety.app.navigation, C/app/navigation/TaskEditorRoute.kt
@Serializable
data class TaskEditorRoute(val taskId: String? = null, val scheduledAt: Long? = null)
```

Он открывает `TaskEditorScreen(taskId, initialScheduledAt, onClose, modifier)`
в пакете `ru.lifety.feature.tasks`. Отсутствующий `taskId` означает новую задачу;
`scheduledAt` задаёт начальное время для создания из календаря. Для редактирования
передаётся только id, актуальная задача читается из репозитория. Opaque id не
склеивается с URL: аргументы кодирует typed Navigation. Сохранение/удаление/отмена
возвращают на предыдущий экран. Редактор отображается через ModalBottomSheet,
нижняя навигация скрыта в редакторе. Finance сохраняет свой локальный контракт
`ExpenseEditorDialog`, но тоже отображает форму в ModalBottomSheet.

Стабильная точка открытия planner из любой функции без импорта `feature.tasks`:

```kotlin
// ru.lifety.core.navigation, C/core/navigation/PlannerNavigation.kt
interface PlannerNavigation {
    fun openTask(id: String)
    fun createTask(scheduledAt: Long? = null)
}
// LocalPlannerNavigation.current?.openTask(task.id)
// LocalPlannerNavigation.current?.createTask()
```

`LocalPlannerNavigation` предоставлен корнем вместе с репозиториями. Новые
верхние routes добавляет Codex 1. Feature-local навигация остаётся у функции.

Все шесть экранов уже зарегистрированы в NavHost корневого файла. Codex 2/3 заменяют
тело своего `XxxScreen`, сохраняя имя, пакет и сигнатуру. `modifier` применяется
к корневому UI-элементу. Feature-код не импортирует другие feature-пакеты;
dashboard/assistant работают с общими моделями и репозиториями.

## Сеть и Android notifications

`ru.lifety.core.network.createHttpClient(): HttpClient` — expect/actual-фабрика:
OkHttp на Android, Darwin на iOS, JSON ContentNegotiation, `expectSuccess = true`,
таймаут соединения 15 с, запроса и сокета 30 с. Владелец клиента закрывает его.
Фабрика не задаёт URL, ключ, AI provider и не отправляет запросов при запуске.
Codex 3 размещает AI-логику в `feature/assistant/ai/**`, использует фабрику;
новые общие зависимости запрашивает у Codex 1. API-ключи не коммитить.
Legacy API (предыдущий экран): `AiProvider`/`KtorAiProvider`, `ContextBuilder`,
`AssistantController` реализованы
в feature-пакете без изменения Core. Разрешения всех категорий default-off,
preview фиксирует JSON до явного Analyze; ключ хранится только в памяти процесса.
Никаких записей в репозитории из AI. Полные контракты и ограничения —
`C/feature/assistant/ai/README.md`. Health наблюдает `HealthRepository.observeAll()`;
ручной ввод использует `update`, подтверждённый пакет — `updateAll`.
Dashboard сохраняет прежнюю подписку на последние показатели. Android Health Connect
читает дневные агрегаты сна/шагов за 30 дней только по явному действию, сохраняет
пакет после предпросмотра и подтверждения. Модель неизменна. Правила группировки,
совместимости и повторного импорта — в `docs/HEALTH_HISTORY.md`. Разрешения AI от этого
не меняются. На iOS доступны ручной ввод и явный HealthKit-импорт через Swift host
с предпросмотром (`docs/IOS_PORT.md`). Android-only connect-client 1.2.0-alpha06 использует
READ_SLEEP/READ_STEPS и Activity объяснения. SDK и его bound service включаются
только на API 28+, minSdk приложения остаётся 24 с точечным overrideLibrary.
Записей в Health Connect, фонового чтения и новых разрешений нет. Добавлен только
SQL-запрос истории; таблицы, schema version и DI прежние.
Точный workflow и ограничения — C/feature/health/INTEGRATION.md.

Android notification adapter реализован Codex 2 в
`ru.lifety.platform.notifications`. `ExpenseNotificationListener` зарегистрирован
в `androidApp/src/main/AndroidManifest.xml` с service permission
`android.permission.BIND_NOTIFICATION_LISTENER_SERVICE`, `exported=false`, без
отдельного процесса. Не требуется runtime POST_NOTIFICATIONS и не добавляется
uses-permission BIND_NOTIFICATION_LISTENER_SERVICE. Сам пользователь выбирает
банки, включает consent и выдаёт notification listener access в Android Settings.
По умолчанию импорт выключен. Сервис не сохраняет расходы автоматически.
Точные ограничения и инструкция демонстрации —
`C/feature/finance/INTEGRATION.md`. Живой банковский push и системное разрешение
пока не тестировались; подтверждение демо-уведомления проверено в Android.

## Agent API и текущий чат

Пакет `ru.lifety.feature.assistant.agent` владеет AgentRuntime, AgentSession,
AgentRunner, AgentWorkspace и LifetyAgentEnvironment. `AssistantScreen` подключён
к runtime и `AgentChatController` после интеграции `9f8b945`/`8dc4ddf`; поддерживает
Markdown, историю Health по разрешению, память и подтверждение proposals.
Android: `createAndroidAgentRuntime(applicationContext)`; iOS:
`createIosAgentRuntime()`. Сессия получает существующий `Repositories?`.
Scopes default-off, HTTP начинается только с `session.start`; записи приложений —
через proposals и явный `runner.approve`. Health read-only.
Android vault.save сохраняет ключ через Keystore; iOS — через host Keychain.
Legacy `feature/assistant/ai` остаётся в коде, но не является текущим экраном.
QuickJS 1.0.15 — common dependency, для host runtime подставляется JVM artifact.
SQL/Manifest не меняются. Точный API, лимиты и lifecycle —
C/feature/assistant/agent/INTEGRATION.md. Результаты актуальной проверки iOS —
`docs/IOS_PORT.md`; прежняя проверка headless API — `docs/NATIVE_AGENT_REVIEW.md`.

## Параллельная работа следующих трёх агентов

1. Все читают этот контракт и начинают от одного commit фундамента. Каждый получает
   собственную ветку и worktree, не отдельный Git-репозиторий.
2. Codex 1: `core/**`, `database/**`, tasks/calendar, реальный composition root,
   Gradle/Manifest/навигация. Codex 2: finance/notifications. Codex 3:
   assistant/dashboard/health/AI. Вне своей зоны — только согласованный запрос владельцу.
3. Codex 2/3 могут сразу разрабатывать UI/state с общими интерфейсами и локальными
   тестовыми fake; SQLite-репозитории и production DI уже доступны в `feature/core`.
4. Новая зависимость, модель, route, миграция, permission или receiver — сначала
   точная сигнатура/патч-запрос Codex 1. Не править общие файлы одновременно.
5. Каждый коммитит только свою область и проверяет Android-сборку и host-тесты.
   Интеграция веток выполняется отдельным поручением; текущая задача делает только
   commit в feature/core, без merge в main.
6. Не обновлять версии стека во время 7-часового хакатона без конкретной причины
   и согласованной проверки сборки. Не создавать сервер, облачную БД, регистрацию,
   векторный поиск или второе SQLite-решение.

Историческое распределение worktree (после очистки прежние feature-ветки удалены,
`lifety-core` работает на `main`, остальные каталоги сохранены с detached HEAD):

| Ветка | Worktree |
| --- | --- |
| `feature/core` | `/Users/rugilf/Projects/lifety-core` |
| `feature/finance` | `/Users/rugilf/Projects/lifety-finance` |
| `feature/assistant` | `/Users/rugilf/Projects/lifety-assistant` |

Не переключать чужие ветки, не редактировать их working tree и не создавать
повторные ветки для уже подготовленных агентов. Каждому worktree нужен свой
игнорируемый `local.properties` с `sdk.dir` либо `ANDROID_HOME`. Финансовая и
assistant-ветки могут продолжать работу с прежними интерфейсами; при последующем
объединении core-коммит подключит реальные репозитории к тем же экранным входам.

## Planner P0: поведение и границы

- `TasksScreen` создаёт/редактирует/удаляет задачи, отмечает выполнение;
  фильтры «Актуальные», «Завершённые», «Все». Список сортируется для UI отдельно
  от repository order. Пустое название запрещает только редактор; модели и
  репозитории не получают новых ограничений. Название перед сохранением trim.
- План и дедлайн — независимые nullable даты с временем. Они могут быть в прошлом;
  редактирование не меняет id и completion без явного действия пользователя.
- `TaskEditorViewModel` сохраняет черновик при configuration change. Несохранённый
  черновик при завершении процесса не гарантируется; сохранённые данные остаются в SQLite.
- `CalendarScreen` — месячная сетка с понедельника, выбор дня и отметки наличия задач.
  Показывает задачи по локальной дате `scheduledAt` **или** `deadlineAt`. Если даты
  совпали, задача отображается один раз. Задачи без обеих дат в календаре не видны.
  Новая задача выбранного дня получает начальное время 09:00 локально, которое
  можно изменить в редакторе. Нажатие на задачу открывает тот же редактор.
- `C/core/time/PlannerDates.kt` содержит KMP-конвертацию локальных дат/времени в
  epoch milliseconds. DatePicker трактуется как civil date в UTC; timezone
  применяется только при объединении с локальным временем. Несуществующее время
  при spring-forward DST отклоняется UI, неоднозначное время при осеннем переходе
  выбирается по стандартной семантике kotlinx-datetime (раннее смещение).
- `C/core/planner/TaskListViewModel.kt` — общий StateFlow state задач/календаря;
  в `C/core/ui/TaskCard.kt` и `LifetyTheme.kt` находятся общий компонент и M3-тема.
- Нет recurrence, приоритетов, длительности или напоминаний о дедлайнах. Контракты моделей
  не расширены. Notifications — зона Codex 2, AI/health UI — Codex 3.

## Версии и источники

Версии зафиксированы в `gradle/libs.versions.toml`; wrapper — Gradle 9.5.1.
Kotlin/Compose compiler 2.4.20, Compose Multiplatform 1.12.1, Material3 1.9.0 (stable), AGP 9.3.1,
Coroutines 1.11.0, Serialization 1.11.0, Ktor 3.6.0, SQLDelight 2.4.0,
Activity Compose 1.13.0. Добавлены Navigation Compose 2.9.2, Lifecycle ViewModel Compose 2.9.2
и kotlinx-datetime 0.7.1, desugar_jdk_libs 2.1.5 для java.time на Android API 24–25;
прежние версии не обновлялись. JDK для проверки — 21, JVM bytecode — 17.
Android minSdk 24, compileSdk/targetSdk 37.

При выборе сверены официальные источники:
[KMP compatibility](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html),
[Compose compatibility](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html),
[Android KMP plugin](https://developer.android.com/kotlin/multiplatform/plugin),
[AGP 9.3](https://developer.android.com/build/releases/agp-9-3-0-release-notes),
[SQLDelight releases](https://github.com/sqldelight/sqldelight/releases),
[Ktor releases](https://github.com/ktorio/ktor/releases),
[Coroutines releases](https://github.com/Kotlin/kotlinx.coroutines/releases),
[Serialization releases](https://github.com/Kotlin/kotlinx.serialization/releases),
[Compose Navigation](https://kotlinlang.org/docs/multiplatform/compose-navigation-routing.html),
[kotlinx-datetime](https://github.com/Kotlin/kotlinx-datetime),
[Android API desugaring](https://developer.android.com/studio/write/java8-support).
Фактические результаты сборки и ограничения проверки — в README.

## API compatibility: OpenRouter

После main 66af7d7 проверен OpenRouter / Nemotron. Общая core HTTP factory
сохраняет таймауты 15/30/30 секунд; оба AI clients отдельно задают request/socket
120 секунд. Бюджет runner 180 секунд и cancellation сохраняются.
AgentMessage/ModelReply получили только optional reasoningDetails/reasoning для
точного round trip метаданных провайдера; старые private checkpoints читаются.
Core models, repository interfaces, SQL и public screen signatures не менялись.
Настройки и фактические live test results — docs/OPENROUTER_API.md.

## iOS platform bindings — 26 октября 2026

`C/app/PlatformNavigationBar.kt`: общий expect, actual Android сохраняет Material 3,
actual iOS использует UIKit UITabBar с SF Symbols. Маршруты и сигнатуры экранов прежние.
`C/core/ui/PlatformStyle.kt` — внутренний expect/actual `isIos` для платформенных
подписей и скрытия недоступного notification listener.

`I/core/platform/IosServices.kt`: публичные host-протоколы `IosCredentialStorage`
(read/write), `IosHealthSource` (available, requestAccess, readDays, cancelRead).
Swift-реализации находятся в `iosApp/Lifety`. HealthSnapshot и репозитории прежние.
Keychain сохраняет provider settings + key одним элементом с
WhenUnlockedThisDeviceOnly. HealthKit не сообщает статус read-разрешений; UI не
делает вывода о разрешении из успешного показа диалога или пустого результата.
Ручные и подтверждённые импортированные записи используют одну SQLite БД.
После локальной проверки владелец разрешил публикацию iOS-порта в main;
предыдущее состояние main (`f8c942e`) сохранено в ветке `HSE-hack-2026`.

## Дневной план — 26 октября 2026

Task, repository interfaces, SQL и screen entry signatures сохранены.
`core/planner/DayPlan.kt`: `dayPlan(tasks, date, now, zone)`, `plannerTimestamp`,
`taskTimingError` и `formatPlannerTime`. Группировка — read-only; просрочка
переносится только в сегодня, дубли внутри дня исключены.
`TaskCard` получил optional `now` для актуального отображения сроков.
Редактор предлагает для нового дедлайна 23:59, для времени выполнения — 09:00;
расписание и срок можно удалить независимо. Только editor дополнительно
проверяет scheduledAt <= deadlineAt для активного дела с будущим дедлайном.
Просроченное дело можно перепланировать без удаления исходного срока. Репозитории не меняют
старые данные и не получают новой валидации. Поведение — `docs/DAY_PLANNER.md`.

## Редактор категорий / iOS overlay — 27 октября 2026

- `core/model/ExpenseCategory.kt`, пакет `ru.lifety.core.model`:
  `@Serializable data class ExpenseCategory(id: String, name: String, colorHex: String, archived: Boolean = false)`.
- `core/repository/ExpenseCategoryRepository.kt`: `observeAll(): Flow<List<ExpenseCategory>>`,
  `suspend upsert(category: ExpenseCategory)`. Выдаёт пользовательские определения
  и переопределения стандартных категорий, включая скрытые; UI дополняет defaults.
- `Repositories` получил `expenseCategories: ExpenseCategoryRepository? = null` в
  конце конструктора, сохраняя прежние вызовы. Production DI всегда передаёт
  `SqlDelightExpenseCategoryRepository`; preview без репозитория не предлагает запись.
- Schema v2, migration `1.sqm`: только новая таблица `expenseCategory`. Expense и
  остальные модели/методы репозиториев/route IDs не меняются. `Expense.category`
  остаётся ID; название и цвет меняются без перезаписи расходов.
- Built-in IDs прежние; новые — `custom:<UUID>`. Архивирование сохраняет определение,
  но исключает его из выбора новых расходов. Скрытую категорию старого расхода
  по-прежнему можно оставить в редакторе. Есть возврат; последнюю видимую скрыть нельзя.
- `FinanceScreen(modifier)` остаётся прежним. `FinanceContent` получил optional
  `onManageCategories: (() -> Unit)? = null`. Контроллер/компоненты категорий internal.
- iOS UITabBar использует `UIKitInteropProperties(placedAsOverlay = true)` и
  прозрачный UIView. Android NavigationBar остаётся в Scaffold. Internal
  `core/ui/LocalBottomNavigationInset` равен высоте overlay только на iOS; функции
  используют его для хвоста списка, FAB и composer, не обрезая фон под стеклом.

## Люди и дни рождения — 27 октября 2026

Ниже показана текущая аддитивная модель, включая дополнение от 1 ноября.
Точные правила своих разделов и фото — в конце документа.

Все новые Kotlin-файлы ниже находятся в `shared/src/commonMain/kotlin/ru/lifety/`.

```kotlin
// core/model/Person.kt — ru.lifety.core.model
@Serializable
data class Person(
    val id: String,
    val fullName: String,
    val birthdayMonth: Int? = null,
    val birthdayDay: Int? = null,
    val birthYear: Int? = null,
    val interests: String = "",
    val hobbies: String = "",
    val giftIdeas: String = "",
    val noteSections: List<PersonNoteSection> = emptyList(),
    val avatarBase64: String? = null,
)
@Serializable
data class PersonNoteSection(val id: String, val title: String, val items: List<String> = emptyList())
// core/repository/PersonRepository.kt — ru.lifety.core.repository
interface PersonRepository {
    fun observeAll(): Flow<List<Person>>
    suspend fun upsert(person: Person)
    suspend fun delete(id: String)
}
```

- `Repositories.people: PersonRepository? = null` — дополнительное поле в конце
  конструктора, прежние вызовы совместимы. `AppContainer` передаёт один
  `database.SqlDelightPersonRepository`. Preview без него показывает явное отсутствие хранилища.
- `observeAll` выдаёт снимок и последующие изменения. SQL: `fullName COLLATE NOCASE,
  id ASC`; UI отдельно сортирует по ближайшему дню рождения/имени. SQLite NOCASE
  не обещает русскую локализованную сортировку. Записи на IO, upsert заменяет строку,
  отсутствующий delete успешен. Ошибка/отмена доходят до вызывающего кода.
- `core/model/PersonValidation.kt`: `personValidationError(person, currentDate)`;
  модель хранит гражданскую дату, а не timestamp. День/месяц либо оба null, либо
  корректная дата; год необязателен. Подробности — [PEOPLE.md](PEOPLE.md).
- **Schema v3 на момент добавления людей**: таблицы `task`, `expense`, `healthSnapshot`,
  `expenseCategory`, `person`. `2.sqm` добавляет `person`, `1.sqm` сохранён.
  Adapter для nullable Int подключён в `createDatabase`.
- `feature/people/PeopleScreen.kt`, пакет `ru.lifety.feature.people`:
  `@Composable fun PeopleScreen(modifier: Modifier = Modifier)`.
  `PeopleViewModel.state: StateFlow<PeopleState>`; редактор internal
  `PersonEditorSheet(initial, isNew, repository, onDismiss)` используется и календарём.
- Вход **План → Люди**, третья внутренняя вкладка. Пять основных разделов и
  публичные navigation/Task/Expense/Health контракты сохранены. Календарь получает
  `.people` из общего DI, не обращается напрямую к SQL.
- `feature/people/PeopleDates.kt`: `birthdayInYear`, `nextBirthday`,
  `birthdaysOnDate`, `birthdayLabel`, `birthdayAgeLabel`. Ежегодная проекция
  без Task-копий; 29 февраля → 28 февраля в невисокосный год.
  Редактирование/удаление человека сразу отражается в календаре.
- Нет новых разрешений или внешнего календаря. Позднее добавлен явный opt-in
  текстовых карточек в контекст ИИ; фотографии туда не передаются.


## Списки в карточках людей / нативная дата iOS

- Person / PersonRepository и хранение людей из schema **v3** не меняются. `interests`, `hobbies`,
  `giftIdeas` остаются String: один пункт на строку, без JSON. Старый текст
  отображается списком по переносам строк; запятые и URL не разделяются.
- `feature/people/PersonNotes.kt`: internal codec `personNoteItems`,
  `personNoteText`, `replacePersonNoteItem`, `personNotePreview`. Порядок сохраняется,
  пробелы по краям и пустые строки убираются при сохранении. Лимит 10 000 символов
  относится ко всему полю, а не к каждому пункту.
- `PersonNoteListEditor.kt`: отдельные редактируемые строки с удалением, добавлением,
  фокусом в новой строке и многострочной вставкой. Списки черновика используют
  `rememberSaveable` / `listSaver`, пустые новые пункты в БД не записываются.
- `feature/people/BirthdayPicker.kt`: internal `BirthdaySelection(day, month, year?)`
  и expect `BirthdayPickerDialog(initial, onConfirm, onDismiss)`.
  `androidMain/.../people/BirthdayPicker.android.kt` — M3 DatePicker;
  `iosMain/.../people/BirthdayPicker.ios.kt` — настоящий UIKit UIPickerView в
  Compose-диалоге, три колонки, «Без года», native accessibility/касания.
  Native view прозрачный и использует `placedAsOverlay = true`.
- Дата передаётся как гражданский день/месяц/необязательный год. Смена месяца/года
  ограничивает день числом дней этого месяца; неизвестный год допускает 29 февраля.
  Выбор применяется только по «Готово», отмена не меняет поля карточки.

## Глобальные цели и первоначальный обмен планом с ChatGPT — schema v4

Все common Kotlin пути ниже относительно `shared/src/commonMain/kotlin/ru/lifety`.

- `core/model/Goal.kt`: serializable `Goal(id: String, title: String,
  description: String = "", targetAt: Long? = null, isCompleted: Boolean = false)`,
  `TaskPlan(taskId: String, durationMinutes: Int, approach: String = "", goalId: String? = null)`,
  `PlanReport(summary: String, importedAt: Long)`.
- `core/repository/PlanningRepository.kt`: `GoalRepository.observeAll(): Flow<List<Goal>>`,
  `suspend upsert(goal)`, `suspend delete(id)`; `PlanningRepository.observeAll(): Flow<List<TaskPlan>>`,
  `observeReport(): Flow<PlanReport?>`, `suspend snapshot(): PlanSnapshot`,
  `suspend apply(plan: ImportedPlan, now: Long)`. Apply повторно валидирует актуальный
  snapshot и атомарно записывает расписание/детали/разбор. No partial application.
- `Repositories` дополнен `goals: GoalRepository? = null`,
  `planning: PlanningRepository? = null`. `AppContainer` создаёт
  `SqlDelightGoalRepository` и `SqlDelightPlanningRepository` на общем driver.
- Schema **v4**: migration `3.sqm` добавляет goal/taskPlan/planReport; старые
  таблицы и Task/Expense/Health/Person signatures сохранены. Удаление Task
  транзакционно убирает TaskPlan; удаление Goal очищает goalId и сохраняет задачи.
- `core/planning/PlanExchange.kt`: versioned JSON, `PlanSnapshot`, `PlannedTask`,
  `ImportedPlan`, fingerprint, exporter/parser/validator. Полный формат,
  ограничения и ручной сценарий — [CHATGPT_PLANNING.md](CHATGPT_PLANNING.md).
- `core/ui/PlanningProvider.kt` — internal общая реактивная проекция для UI,
  LocalPlanningState / LocalPlanningRetry. Один набор наблюдений на приложение.
- DashboardScreen signature прежняя; цели открываются в
  `feature/goals/GoalManagerSheet.kt`. Пять primary destinations/route IDs сохранены.
- AgentSettingsSheet содержит вход обмена без API-ключа. Контроллер
  `feature/assistant/transfer/PlanTransferController.kt` выдаёт StateFlow состояния,
  preview не меняет БД. Share/file import — expect/actual Android/iOS; JSON — данные,
  не команды и не исполняемый код. Credentials в экспорт не включаются.
- TaskCard/TaskEditor/Calendar/главная получают estimates и approach из общего DI;
  поля Task и обязательные публичные screen signatures не меняются.
- Агент с tasks scope экспортирует актуальные goals/taskPlans/report вместе с tasks
  перед запросом; без scope эти данные удаляются из generated workspace.

## Настройки внешнего вида

- `core/appearance/LifetyPalette.kt`: восемь stable IDs (lifety, tokyo-night,
  catppuccin, gruvbox, nord, lifety-light, catppuccin-latte, gruvbox-light),
  ThemePalette с вычисляемым isDark и имена alternate icons. Три последних — светлые.
- `LifetyTheme(content)` сохраняет сигнатуру; платформа восстанавливает тему
  из SharedPreferences/NSUserDefaults и предоставляет internal LocalAppearance.
  SQL schema v4, repository/model/navigation contracts не меняются.
- `LifetySettingsSheet(onDismiss, onFinance)` сохраняет сигнатуру для совместимости;
  onFinance больше не вызывается, финансового пункта в настройках нет.
- Нативный UITabBar и LifetyMascot используют текущую Material-палитру.
- iOS alternate sets: AppIconTokyoNight, AppIconCatppuccin, AppIconGruvbox, AppIconNord,
  AppIconLifetyLight, AppIconCatppuccinLatte, AppIconGruvboxLight;
  null — основной AppIcon. Android launcher aliases: .LauncherLifety,
  .LauncherTokyoNight, .LauncherCatppuccin, .LauncherGruvbox, .LauncherNord,
  .LauncherLifetyLight, .LauncherCatppuccinLatte, .LauncherGruvboxLight.
  Target MainActivity остаётся включённой; LAUNCHER intent перенесён на aliases.
- Иконка меняется только по отдельной кнопке. UI читает фактическое состояние ОС,
  обработка ошибок не выдаёт неудачу за успешный выбор. Источники/лицензии,
  платформенные ограничения и результаты — [APPEARANCE.md](APPEARANCE.md).

## Постоянный разговор ChatGPT / компактный контекст — schema v5

- Основной AssistantScreen по умолчанию показывает ручной обмен; API-chat находится
  в дополнительных настройках обмена. Публичные screen/route signatures сохранены.
- `core/planning/ChatGptContext.kt`: ChatGptPreferences/Workspace/Draft/Sent/Receipt,
  ExchangeBaseline/ContextChanges, SleepContext/DaySummary/MonthSummary.
  Статистика сна локальная: последнее известное значение на день, 7 дней и
  медианы всех доступных месяцев с coverage, без шагов/сырой истории.
- `core/repository/ChatGptExchangeRepository.kt`: observe(): Flow<ChatGptWorkspace>,
  suspend savePreferences(preferences), saveDraft(draft), markSent(draftId, now).
  Repositories.chatGptExchange nullable default, AppContainer production подключает
  SQLDelightChatGptExchangeRepository. Migration 4.sqm добавляет singleton
  chatGptExchange, актуальная schema v5. Предыдущие модели/таблицы прежние.
- Новый контекст/ответ v2 с requestId; ImportedPlan имеет default deferredTasks
  и requestId, v1 по-прежнему читается и применяется по старым правилам.
  v2 покрывает каждый active task либо слотом, либо DeferredTask(taskId, reason).
  Очередь снимает scheduledAt, сохраняя дедлайн и старые оценки/goal link.
- apply в одной транзакции валидирует задачи и текущий контекст сна/предпочтений,
  сохраняет расписание/очередь/report и квитанцию обмена. Повтор v2 отклоняется.
- Открытие share menu / копирование не считаются отправкой. Baseline обновляет
  только отметка пользователя или применение коррелированного ответа v2.
  Подготовленный текст может содержать выбранный сон; изменение настроек очищает
  draft, отключение сна прекращает ожидание старого ответа. Baseline не хранит сон.
- Private conversation URL необязателен; только HTTPS chatgpt.com/c/... либо
  /g/.../c/..., без автопубликации, без чтения чужого чата. Детали и QA:
  [CHATGPT_PLANNING.md](CHATGPT_PLANNING.md).

## Блокировка разделов / ручное расписание — 28 октября

- `core/security/SectionLockController.kt`: internal SectionLockPlatform,
  LockConfiguration / SectionLockState / SectionLockController. UI provider и
  guard — `SectionLockUi.kt`; Android/iOS actual adapters в зеркальных пакетах.
  Пять primary route IDs; calendar и task editor относятся к tasks.
  Dashboard/Assistant требуют код также при защите tasks/finance/health.
- Root `SectionLockProvider` владеет общим session state, route bodies обёрнуты
  в `SectionGuard`. В репозитории и модельные DTO код/конфигурация не добавляются.
  iOS host подключает `IosServices.sectionLocks: IosSectionLockStorage?` через
  `SectionLockStorage`, существующий API credentials хранится отдельно.
  Настройка защиты — в прежнем LifetySettingsSheet, без новых primary routes.
- PlanningRepository дополнен `suspend saveTask(task: Task, details: TaskPlan?)`
  с default UnsupportedOperationException для совместимости существующих fake
  implementations. SQLDelight implementation атомарно валидирует и сохраняет
  Task + TaskPlan, сохраняя прежнюю schema v5. Старые методы и модели не меняются.
- TaskEditorViewModel принимает optional PlanningRepository; редактор отображает
  и сохраняет длительность, шаги и goalId. Таймлайн не придумывает длительность;
  общий алгоритм — `core/planner/DayTimeline.kt`. Часовой пояс/DST учитываются.
- AssistantScreen сохраняет signature; его scroll content заполняет пространство
  под native tab bar, inset находится в content padding. API composer — overlay.
  Детальные ограничения/проверки: [SECTION_LOCKS.md](SECTION_LOCKS.md),
  [TIME_PLANNING.md](TIME_PLANNING.md).


## 2026-10-30: произвольные показатели, категории ИИ и нижняя панель

- `core/model/HealthMetric.kt`, пакет `ru.lifety.core.model`: `HealthMetric`,
  `HealthEntry`, `HealthMetricSnapshot`, `MetricKind` (NUMBER/TEXT/BOOLEAN),
  `MetricAggregation` (LATEST/SUM/AVERAGE), каталог `healthApiMetrics`.
  Прежние модели Task/Expense/HealthSnapshot/Person не менялись.
- `core/repository/HealthMetricsRepository.kt`, `ru.lifety.core.repository`:
  `observe(): Flow<HealthMetricSnapshot>`, suspend `snapshot()`, `saveMetric(metric)`,
  `saveEntry(entry)`, `deleteEntry(id)`, `importSamples(samples, source)`.
  Определения архивируются, записи сохраняются. Тип нельзя менять после первой записи.
  Ручные числа нормализуются, NaN/Infinity отклоняются; API-строки нельзя
  перезаписать через ручной saveEntry. Импорт read-only, только после предпросмотра.
- `Repositories.healthMetrics` и `.navigation` добавлены nullable с defaults;
  `AppContainer` поставляет реализации SQLDelight. Один dispatcher с parallelism=1
  сериализует операции общей БД и предотвращает пересечение read/write transactions.
- Schema **6**, миграция `5.sqm`: healthMetric, healthEntry (индекс metricId/timestamp),
  navigationPreference. Существующие таблицы и данные сохраняются.
- `feature/health/HealthMetricsSection.kt`: редактор определения, архив, история,
  ручное добавление/редактирование/удаление, график до 90 известных дней.
  `DeviceMetricsSheet.kt` + platform `MetricDeviceSource`: выбранные вес, пульс
  в покое, вода, активная энергия; 30 дней, разрешение → чтение → сохранение.
  iOS `IosServices.healthMetrics: IosMetricSource?`, host `AppleMetricSource`
  в AppleHealthSource.swift; Android Health Connect. Missing ≠ zero.
- Импорт ID = source:key:dayTimestamp; повторный импорт заменяет итог.
  Для дня ручные записи имеют приоритет; SUM/AVERAGE считают ручные записи,
  при их отсутствии берут один итог API. Разные провайдеры не суммируются.
- `core/planning/HealthMetricContext.kt`: summarizeMetric/selectedExtraContext.
  Семь календарных дней (null для пропусков), месячные медианы чисел по известным
  дневным итогам; текст/да-нет только семь дней. Финансы: агрегаты 30 дней по
  валюте/категории, точная строка amountMinor. Люди: явное включение карточек целиком.
- `ChatGptPreferences` дополнены includeTasks/includeGoals (default true),
  includeSteps/includeFinance/includePeople (false), healthMetricIds (empty).
  Ручной редактор запроса хранит выбор. Выключенная категория отсутствует в
  новом JSON; выбор входит в fingerprint, выбранные данные повторно проверяются
  при handoff и атомарном apply. Без задач разрешён обычный вопрос, импорт плана
  отключён. Изменение настроек удаляет draft и закрывает ожидание прежнего ответа.
- API `AgentScopes` дополнен healthSleep/healthSteps (true для совместимости),
  healthMetricIds (empty); общий health по-прежнему false до явного включения.
  API workspace содержит summary.json и выбранные sleep/steps только за 7 дней,
  сырая более ранняя история не экспортируется. Смена scopes очищает workspace/чат.
  Предыдущие отправки внешнему сервису отозвать нельзя.
- `NavigationPreferencesRepository`: observe(): Flow<List<String>>, suspend save(routes).
  Допустимы 1–5 уникальных routes; порядок сохраняется локально в SQLite.
  `app/navigation/NavigationPreferences.kt`: defaultNavigation/availableNavigation,
  NavigationProvider, LocalNavigationItems. В native iOS используются стабильные
  enum tags, прозрачный overlay сохранён. Android использует те же routes/порядок.
- Прежние routes неизменны; новый **people** → `PeopleScreen(modifier)`.
  Редактор: Настройки → Нижняя панель. Добавляются calendar/people; скрытые разделы
  доступны через «Все разделы» сверху. Calendar и People наследуют защиту «План».
  При отдельном Calendar в панели «План» открывает Tasks; без него помнит вкладку.

Подробности и проверка: [CUSTOM_HEALTH_AND_NAVIGATION.md](CUSTOM_HEALTH_AND_NAVIGATION.md).


## 2026-10-30: переработка сценариев ввода и источников

`HealthScreen(modifier)` теперь показывает обзор и один host ModalBottomSheet:
выбор записи, форма, детали, редактор определения, архив и источник. Периоды и
история находятся в деталях показателя. Числа/даты берутся из прежних репозиториев.
`MetricEditorContent` / `MetricDetailContent` не создают вложенные sheets.
`DeviceMetricsContent` объединяет шесть типов через `MetricDeviceSource`:
sleep/steps + weight/resting-heart-rate/water/active-energy. Разрешения — только
по кнопке получения, сохранение — отдельное. Старый HealthConnectSection остаётся
совместимым платформенным компонентом; root больше не выводит второй импорт.

Добавлен `HealthMetricsRepository.importDeviceSamples(samples, source, baseline)`
с default UnsupportedOperationException. SQLDelight implementation атомарно
проверяет прежнюю health history, сохраняет выбранные встроенные значения и
дополнительные показатели. Не выбранные поля сохраняются; invalid/changed preview
откатывает транзакцию. Максимум 180 дневных значений (6 × 30); старый importSamples
для четырёх дополнительных типов не меняет контракт. Schema остаётся 6.

ConversationSettingsSheet: «Данные / Разговор», прежний явный save preferences.
TaskDateTimeField сворачивает controls, сохраняя value/callback; TaskEditorScreen
раскрывает duration/approach/goalId по действию, не очищая их. Routes, entry signatures,
публичные прежние модели, права ИИ, темы и native tab overlay сохранены.

Источники решений и проверка: [UX_REDESIGN.md](UX_REDESIGN.md).


## Нативные элементы iOS — 30 октября 2026

Публичные модели, репозитории, маршруты, DI, сигнатуры экранов и схема 6 не меняются.

- `C/app/PlatformTopBar.kt`: внутренний expect `PlatformTopBar(title, showMascot,
  onSections, onSettings)`. Actual Android — прежняя TopAppBar с маскотом;
  actual iOS — прозрачная UINavigationBar, SF Symbols, верхняя safe area.
  Нижний UITabBar и его overlay/inset контракт не меняются.
- `C/core/ui/LifetyDateField.kt`: прежняя публичная сигнатура теперь expect;
  actual `A/core/ui/LifetyDateField.android.kt` сохраняет Material DatePicker,
  `I/core/ui/LifetyDateField.ios.kt` использует компактный UIKit UIDatePicker.
  Значение остаётся ISO civil date. Для UIKit дата кодируется локальным полуднем
  и декодируется в том же часовом поясе; UTC midnight остаётся Android-контрактом.
  В нативном календаре понедельник — первый день недели.
- `C/feature/tasks/TaskDateTimeField.kt`: прежняя внутренняя сигнатура — expect.
  Actual Android перенесён без изменения поведения; actual iOS — необязательные
  значения с «Указать»/«Убрать» и компактным UIDatePicker dateAndTime.
  Все изменения относятся к черновику редактора; запись только через Save.
- `C/core/ui/LifetyTimeField.kt`: внутренний expect `LifetyTimeField(label, hour,
  minute, onChange, modifier, enabled)` для окончания задачи. Android использует
  TimeInput dialog, iOS — native time-only picker. Время без даты кодируется в UTC;
  существующий `durationUntil` считает длительность в часовом поясе плана.
- `C/core/ui/LifetySegmentedControl.kt`: внутренний expect с labels, индексом
  selected, onSelect, modifier, enabled. Android — Material segmented buttons,
  iOS — UISegmentedControl. Индекс -1 означает произвольный диапазон финансов.
- `C/core/ui/LifetySwitch.kt`: внутренний expect checked/onCheckedChange/modifier/
  enabled. Android — M3 Switch, iOS — UISwitch с intrinsicContentSize текущей ОС.
- `I/core/ui/IosDatePicker.kt`: только UIKit binding; callbacks используют
  rememberUpdatedState, targets удаляются на release. Встроенные controls
  находятся под Metal canvas и кооперативно передают жесты прокрутке. Overlay
  нужен только фиксированным верхней и нижней панелям, не содержимому форм.
- LifetyTheme выбирает iOS text scale 34/28/22/17/15/13 sp и более мягкие углы
  inputs только на iOS; общий источник палитр и системное масштабирование сохранены.

Формат времени в нативных controls следует настройке iOS 12/24 часа; сохранение
по-прежнему epoch milliseconds, общие текстовые подписи плана — 24-часовые.
Границы ручной проверки — [IOS_NATIVE_UI.md](IOS_NATIVE_UI.md).


## Расписание, заметки расходов и iOS-меню — schema 8 на момент добавления

Дополнения совместимы со старыми вызовами. `Task`, `TaskRepository`,
`HealthSnapshot`, `HealthRepository` и существующие входы `XxxScreen(modifier)`
сохранены. Путь `C` ниже — `shared/src/commonMain/kotlin/ru/lifety`.

```kotlin
// C/core/model/ScheduleBlock.kt, package ru.lifety.core.model
@Serializable data class ScheduleBlock(
    val id: String,
    val title: String,
    val startMinute: Int,
    val endMinute: Int,
    val weekdays: Set<Int> = emptySet(),
    val date: String? = null,
)
// C/core/repository/ScheduleRepository.kt, package ru.lifety.core.repository
interface ScheduleRepository {
    fun observeAll(): Flow<List<ScheduleBlock>>
    suspend fun upsert(block: ScheduleBlock)
    suspend fun delete(id: String)
}
```

- Минуты местных суток 0..1439. ISO weekdays 1=Пн..7=Вс. Либо непустое множество
  дней и date=null, либо date=ISO civil date и пустые weekdays. End <= start —
  окончание на следующие сутки (равенство — сутки). Часовой пояс устройства:
  постоянные часы остаются местными при поездках. DST разрешается kotlinx.datetime;
  неположительные интервалы не создаются. Исключений повторений и срока серии пока нет.
- `Repositories.schedule: ScheduleRepository? = null` — добавленный последний
  параметр. `AppContainer` подключает `SqlDelightScheduleRepository` на общем
  dispatcher/connection. `LocalPlanningState.schedule` наблюдает тот же Flow.
- `core/planner/Schedule.kt`: `scheduleError`, `scheduleOnDate` →
  `ScheduleOccurrence(block,start,end)`; start/end — UTC epoch milliseconds.
  Ночные занятия присутствуют на обоих днях, без размножения задач в БД.
- `dayTimeline` сохраняет старые параметры, в конце добавляет
  `schedule: List<ScheduleBlock> = emptyList()`. `DayTimeline` дополнен schedule/window;
  busy/free — объединение интервалов задач и занятий в выбранных часах,
  пересечения half-open. Длительность задач без оценки не придумывается.
- `feature/schedule/ScheduleScreen.kt`: `ScheduleScreen(modifier: Modifier = Modifier)`.
  Четвёртая вкладка Плана, индексы День=0/Календарь=1/Люди=2 сохранены.
  Общая сетка `feature/tasks/DayTimeGrid.kt`; календарь и сетка открывают общий
  `ScheduleEditorSheet`. Корневые route IDs и 1–5 настраиваемых разделов не меняются.
- SQL `Lifety.sq`, migration `7.sqm` (7→8): новая scheduleBlock с weekday bitmask
  (бит0=Пн). Создание/обновление/удаление реактивны. Migration `6.sqm` (6→7)
  добавляет expense.note TEXT DEFAULT '' и navigationPreference.presentation
  TEXT DEFAULT 'tabs'; старые расходы/настройки сохраняются.
- `Expense` дополнен последним `note: String = ""`. Заметка до 2000 символов,
  редактируется отдельно от merchant, хранится и показывается в истории. Старый
  сериализованный JSON читается; agent expense_upsert сохраняет имеющуюся note.
- `PlanSnapshot` дополнен `schedule: List<ScheduleBlock> = emptyList()`.
  Snapshot транзакционный; fingerprint включает занятия и нормализованные weekdays.
  ChatGPT v1/v2 получает fixedSchedule, когда включены задачи. Обновление расписания
  делает старый draft/ответ устаревшим; apply проверяет занятые интервалы повторно.
  Встроенный агент получает read-only data/fixedSchedule при доступе к задачам.
- `NavigationPreferencesRepository` дополнен observePresentation и saveConfiguration.
  SQL сохраняет routes+presentation атомарно; старый save(routes) сохраняет вид.
  Значения tabs/arc. Android использует прежнюю панель; iOS выбирает native
  UITabBar или `IosArcNavigationFactory` из IosServices. Контракты bridge —
  `shared/src/iosMain/kotlin/ru/lifety/core/platform/IosArcNavigation.kt`;
  SwiftUI factory/session/model/view — `iosApp/Lifety/LifetyApp.swift`.
  Обычная панель по умолчанию, отсутствие factory даёт UITabBar fallback.
- SectionGuard автоматически передаёт ровно шесть введённых цифр unlock;
  wizard создания/смены кода остаётся с подтверждением. Rate limit и хранение
  защиты не менялись. Автопроверка не создаёт и не сохраняет credentials.
- `core/ui/LifetyTextField.kt`, `NativeSurfaceColor.kt`, `SheetSwipe.kt` — общий
  iOS form/gesture слой. Бизнес-логика остаётся в commonMain, полная миграция
  экранов на SwiftUI не выполнялась.

Проверки и ограничения текущего изменения: [SCHEDULE_AND_IOS_FIXES.md](SCHEDULE_AND_IOS_FIXES.md).


## Оптимизация — 31 октября 2026

Публичные модели/репозитории/route IDs и schema 8 не меняются.
Внутренние `core/planner/IntervalIndex.kt` и `feature/finance/FinancePresentation.kt`
содержат чистые расчёты. Finance/Tasks/Health публикуют фоновые результаты через
отменяемую Compose-корутину; идентичность расчёта отделяет новый фильтр от старого
результата. Главная и проверки ChatGPT используют Default; транзакционная повторная
проверка `PlanningRepository.apply` остаётся обязательной. ViewModel/repository
порядок истории сохранён.

Динамические planning/navigation locals отслеживают читателей. Постоянный DI
остаётся static; экземпляры репозиториев не создаются в экранах. UIKit appearance
кешируется в пределах жизни native view и сбрасывается при release. iOS получает
мгновенные tab/segment transitions, Android сохраняет fade. Дополнительная схема
`iosApp/Lifety.xcodeproj/xcshareddata/xcschemes/Lifety Performance.xcscheme` запускает
Release без LLDB; старую Debug-схему не заменяет. Результаты и границы —
[PERFORMANCE.md](PERFORMANCE.md).

## Свои разделы и фото людей — 1 ноября 2026, актуальная schema 9

- `core/model/Person.kt`, `ru.lifety.core.model`: к концу `Person` добавлены
  `noteSections: List<PersonNoteSection> = emptyList()` и `avatarBase64: String? = null`.
  `PersonNoteSection(id: String, title: String, items: List<String> = emptyList())`
  сериализуемый, ID стабилен при переименовании. Старые поля и `PersonRepository`
  сохранены; старый JSON читается с дефолтами. Первые три списка остаются String
  с пунктами через перенос строки; свои списки — структурированные.
- `PersonValidation.kt`: до 20 своих разделов на человека, уникальные непустые ID
  до 100 символов, уникальные без учёта регистра названия до 80 символов.
  Имена трёх встроенных разделов зарезервированы. До 100 пунктов / 10 000 символов
  на раздел и 40 000 символов суммарно в своих разделах. `normalizedPerson` обрезает
  края названий, разбивает многострочные пункты и убирает пустые строки.
- `database/SqlDelightRepositories.kt`: `personFromRow` декодирует JSON разделов;
  оба потребителя `selectPeople` используют его. `upsert` проверяет исходную и
  нормализованную карточку, выполняется на IO. Общий DI/Flow/navigation не меняется.
- `Lifety.sq`: `person.noteSections TEXT NOT NULL DEFAULT '[]'`,
  `person.avatarBase64 TEXT`. `8.sqm` добавляет оба поля; schema 8→9 оставляет
  прежние строки/дни рождения/заметки. Другие таблицы не изменены.
- Фото — центральный квадрат JPEG 512×512, до 180 000 байт / 240 000 Base64-символов,
  константы `MAX_PERSON_AVATAR_BYTES`, `MAX_PERSON_AVATAR_BASE64`. Оригинал/URI/EXIF
  не сохраняются. Репозиторий проверяет Base64, длину и JPEG envelope; UI безопасно
  показывает инициалы, если декодирование изображения не удалось.
- `feature/people/PersonAvatar.kt`: internal composable аватара, декодирование
  на Default, expect `rememberPersonPhotoPicker(onPicked, onFinished, onError): () -> Unit`
  и `decodePersonAvatar(bytes): ImageBitmap?`. Actual-файлы `PersonAvatar.android.kt`
  и `PersonAvatar.ios.kt` находятся в соответствующих source sets того же пакета.
- Android использует существующий Activity Result `PickVisualMedia(ImageOnly)`
  с AndroidX fallback; на API 28+ ImageDecoder, на 24–27 BitmapFactory/ExifInterface.
  Нет новых зависимостей или разрешений Manifest.
- `iosMain/core/platform/IosPersonPhotos.kt`: bridge `IosPersonPhotoPicker.pick`
  возвращает отменяемый `IosPersonPhotoRequest`; подключён через
  `IosServices.personPhotos`. `iosApp/Lifety/LifetyApp.swift` регистрирует PHPicker
  и подготовку миниатюры через ImageIO. Выбор отменяется при уничтожении редактора,
  completion приходит на main. Отмена выбора не стирает прежнее фото.
- `PersonEditorSheet` сохраняет разделы/фото только по «Сохранить»; во время
  обработки фото сохранение выключено, закрытие доступно. Свой раздел сворачивается,
  название/список редактируются, удаление подтверждается. Поиск включает свои заметки;
  фото видно в списке людей и событиях дней рождения. Экранные сигнатуры прежние.
- `Person.textContext()` удаляет фото перед сериализацией выбранных карточек
  в `HealthMetricContext`. Свои разделы входят только при существующем opt-in людей.
  Смена одного фото не меняет текстовый контекст. Автоматической отправки нет.
- Проверки, сценарии и пределы — [PEOPLE_SECTIONS_AND_PHOTOS.md](PEOPLE_SECTIONS_AND_PHOTOS.md).
