# Lifety — Core, Planner, Finance & Assistant

Kotlin Multiplatform + Compose Multiplatform, Android-first. Реализованы локальные
SQLDelight-репозитории, задачи, календарь, общий DI и Compose Navigation.
Finance обновлён до `origin/feature/finance` (`c703a1f`): CRUD, периоды,
категории, месячный лимит и подтверждённый импорт уведомлений.
Из `origin/feature/assistant` (`e30a688`) подключены обзор локальных данных,
ручной ввод сна/шагов и опциональный AI с явным предпросмотром контекста.
Обновление `feature/assistant` (`14a4311`) добавляет Android Health Connect:
выбор сна/шагов, явное чтение, предпросмотр и подтверждение локального сохранения.
Текущая интеграция описана в [docs/INTEGRATION_STATUS.md](docs/INTEGRATION_STATUS.md).

Рабочая ветка: **main**, worktree: `/Users/rugilf/Projects/lifety-core`.
Ветка **HSE-hack-2026** сохраняет состояние main до iOS-порта (`f8c942e`).
Прежние feature/codex-ветки удалены по запросу владельца; их Git-история
дополнительно сохранена в локальном bundle. Рабочие папки агентов сохранены.

## Скорость на iPhone

Оптимизированы переключение разделов, подготовка финансов/плана/истории и
перерисовка графиков. В Xcode есть **Lifety Performance**: Release без отладчика
для проверки скорости на телефоне. Обычная **Lifety** сохраняет Debug.
Что изменено, замеры и ограничения — [docs/PERFORMANCE.md](docs/PERFORMANCE.md).

## Свои разделы и фото людей

**План → Люди → карточка человека**: «Добавить свой раздел» создаёт именованный
список, например «Любимые места» или «Размеры одежды». Название и пункты можно
редактировать, раздел — убрать. Прежние интересы, увлечения и идеи подарков сохранены.
«Добавить фото» открывает системный выбор фотографии на iOS/Android; фото можно
заменить или убрать. Изменения применяются кнопкой «Сохранить».

Актуальная SQLite schema — **9**: миграция 8→9 сохраняет существующие карточки.
Фото хранится локально и исключено из контекста ИИ. Проверены Android build/lint,
273 host tests и 146 iOS tests, Xcode simulator build и сценарий на iPhone Simulator.
Подробности и ограничения — [docs/PEOPLE_SECTIONS_AND_PHOTOS.md](docs/PEOPLE_SECTIONS_AND_PHOTOS.md).

## Расписание, исправления ввода и дуговое меню

**План → Расписание**: добавление, редактирование и удаление постоянных занятий
по выбранным дням недели либо на одну дату. Начало и окончание задаются отдельно;
ночные интервалы продолжаются на следующий день. План → День показывает занятия
и задачи на общей временной сетке, учитывает их в свободных окнах и отмечает
пересечения. Сетка сворачивается; касание записи открывает редактор, свободного
часа справа — создание задачи. Календарь также показывает занятия.
Расписание включается в запрос ChatGPT вместе с задачами; импорт проверяет занятые часы.

Исправлен порядок текста и положение курсора при создании задачи на iPhone.
У расхода есть отдельная заметка «Что купили». Защищённый раздел открывается после
шестой цифры верного кода. Настройки → Нижняя панель → **Дуга** включают на iOS
центральную кнопку с верхней дугой разделов: удержание или касание раскрывает
настоящее SwiftUI Liquid Glass меню. Обычная панель остаётся вариантом по умолчанию.

У iOS-форм больше нет прямоугольных рамок полей; native controls получают фон
текущей поверхности, а host — выбранной темы. Верхний маркер панели следует за
свайпом и не закрывает окно простым касанием. На этапе этого обновления — schema **8**,
миграции 6→7 (заметки/вид панели) и 7→8 (расписание) сохраняют прежние записи.
Поведение и границы проверки: [docs/SCHEDULE_AND_IOS_FIXES.md](docs/SCHEDULE_AND_IOS_FIXES.md).

## Свои показатели, категории ИИ и нижняя панель

Здоровье открывается обзором последних значений. Кнопка «Запись» добавляет данные
выбранного показателя, «Мои показатели → Добавить» создаёт свой. Графики и история
открываются внутри показателя. Источники данных → Apple Health / Health Connect
объединяет сон, шаги, вес, пульс в покое, воду и энергию в одном предпросмотре.
Ассистент → «Что отправить в ИИ» позволяет выбирать категории и свои показатели.
Для здоровья используются семь дней и месячные медианы, без сырой годовой истории.
Настройки → «Нижняя панель»: 1–5 пунктов, порядок, отдельные Календарь/Люди.
Скрытые разделы доступны сверху через «Все разделы».

На этапе schema 6 были сохранены прежние данные. Тогда проверены Android build/lint, 250 host tests
(6 live LLM tests пропущены без ключей), 124 iOS tests и Xcode simulator build.
Ручная проверка панели на iPhone Simulator пройдена; чтение новых типов из реальных
Health API и полный ручной CRUD на iPhone не проверены.
Инструкция и точные ограничения — [docs/CUSTOM_HEALTH_AND_NAVIGATION.md](docs/CUSTOM_HEALTH_AND_NAVIGATION.md).

Переработка интерфейса по материалам Todoist, Apple Health и Bear, сценарии и
проверки — [docs/UX_REDESIGN.md](docs/UX_REDESIGN.md).

## Нативные элементы iOS

На iPhone верхняя панель — UIKit UINavigationBar с SF Symbols, переключатели
Плана, периодов здоровья/финансов и настроек контекста — UISegmentedControl.
Даты и время выбираются системным UIDatePicker прямо из формы, переключатели
состояния — UISwitch. Android сохраняет Material 3. Контракты и результаты
проверки — [docs/IOS_NATIVE_UI.md](docs/IOS_NATIVE_UI.md).

## Запуск Android

Нужны JDK 21, Android SDK Platform 37 (`platforms;android-37.0`), Build Tools
36.0.0, принятые лицензии SDK и доступ к Google Maven/Maven Central при первой
сборке. Wrapper Gradle 9.5.1 включён в Git. Android minSdk — **24**, target/compile
SDK — **37**. Для java.time на API 24–25 включён core library desugaring.

```sh
cd /Users/rugilf/Projects/lifety-core
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :androidApp:assembleDebug
```

На другой машине используйте свои пути к JDK 21 и Android SDK. Вместо
`ANDROID_HOME` можно задать `sdk.dir=/absolute/path/to/sdk` в игнорируемом
`local.properties`. Gradle JDK в Android Studio тоже выставьте на JDK 21.

Откройте **этот worktree** в Android Studio с поддержкой AGP 9.3, выберите
конфигурацию `androidApp` и устройство API 24+. Для установки из терминала:
`./gradlew :androidApp:installDebug` (требует подключённого устройства).

APK относительно корня worktree:
`androidApp/build/outputs/apk/debug/androidApp-debug.apk`.

## Запуск iOS

Готовый Xcode host: `iosApp/Lifety.xcodeproj`, scheme **Lifety**.
Нужны macOS, Xcode 27.1, JDK 21; iPhone/iPad **iOS 26.5+**, симулятор arm64
на Apple Silicon. Минимум выбран с учётом уже используемых native binaries
QuickJS/Compose; более старые iOS не заявляются поддерживаемыми.

Откройте проект в Xcode, выберите iPhone Simulator и Run. Build Phase сам соберёт
и подключит `LifetyShared` из текущих Kotlin-исходников. CocoaPods, сервер и
генератор Xcode-проекта не нужны. Для терминала:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME="$HOME/Library/Android/sdk"
./scripts/build-ios-simulator.sh
```

Приложение: `build/ios/Build/Products/Debug-iphonesimulator/Lifety.app`.
Для физического iPhone выберите свою Team в Signing & Capabilities, проверьте
HealthKit capability и provisioning profile, затем Run. Team и учётная запись
Apple в исходники не включены. Не следует устанавливать simulator .app на телефон.

Нижняя панель iOS — настоящий UIKit UITabBar с SF Symbols и системным стеклом,
по умолчанию пять разделов; их состав и порядок настраиваются. Health получает
шесть выбранных типов из Apple Health по кнопке и показывает дневные итоги
за 30 дней перед сохранением. API-ключ и настройки
провайдера после явного сохранения хранятся в Keychain, а не в файлах агента.
Банковские уведомления остаются Android-only; на iOS доступен ручной учёт финансов.
Подробности, команды и границы проверки — [docs/IOS_PORT.md](docs/IOS_PORT.md).

На iOS прокрутка форм отделена от перетаскивания панели: содержимое больше не
сдвигает и не закрывает окно на границе списка. Закрытие свайпом доступно на
верхнем маркере. Проверки — [docs/IOS_SHEET_SCROLL.md](docs/IOS_SHEET_SCROLL.md).

## Android UI polish

На базе объединённого main `2518b8a`: стандартная типографика Material 3,
компактные секции и строки, привычные меню/FAB, переходы 120–180 ms,
анимация перемещения элементов и progress bars. Настройки AI/импорта и
пользовательские даты раскрываются по действию; операции записи не ждут анимаций.
Формы закрываются после успешной записи с штатным sheet transition.
Черновик расхода и ручные показатели переживают смену конфигурации.
Нижняя панель скрывается при вводе, доступ к кнопкам проверяется с IME.
Подробные правила и размеры — в `docs/DESIGN_SYSTEM.md`, результаты проверки
и ограничения измерения плавности — в [docs/UI_POLISH.md](docs/UI_POLISH.md).

## OpenRouter API

Проверен OpenRouter с `nvidia/nemotron-3-ultra-550b-a55b:free`.
Полный endpoint для существующего экрана, конфигурация headless runtime,
live tests и ограничения — [docs/OPENROUTER_API.md](docs/OPENROUTER_API.md).
Ключи в проект и APK не включаются.

## Движок агента и чат

Объединены Health (`00bc434`) и `codex/native-agent-harness` (`9f8b945`):
локальная рабочая папка, QuickJS-расчёты, история/память и настоящий чат Assistant.
Запрос начинается после отправки сообщения; доступ к категориям данных выключен
по умолчанию. Предложения изменений требуют явного подтверждения.
Android сохраняет ключ после «Сохранить и подключить» с шифрованием Android Keystore.
Настройки OpenRouter: базовый URL `https://openrouter.ai/api/v1`, модель и свой ключ.
Контракты — `shared/src/commonMain/kotlin/ru/lifety/feature/assistant/agent/INTEGRATION.md`.
Health сохраняет полную локальную историю и графики независимо от настроек AI.
Проверка объединённого состояния: Android assembleDebug, shared host tests,
iOS Simulator arm64 compile — успешны. Результаты ниже, относящиеся к прежним
версиям, сохранены как история проверок.

Проверка объединения: Android build/lint, common metadata и iOS compile успешны;
119 host tests прошли, 2 live eval пропущены без credentials.
Отчёт — [docs/NATIVE_AGENT_REVIEW.md](docs/NATIVE_AGENT_REVIEW.md).

## План на день и дедлайны

План → День теперь показывает интервалы начала/окончания, длительность,
свободные окна и пересечения. Можно менять оценку, шаги и цель прямо в редакторе;
расписание сохраняется локально. Подробнее — [docs/TIME_PLANNING.md](docs/TIME_PLANNING.md).

Раздел План → День: выбор даты, просроченные дела, сроки на день и расписание.
В редакторе дедлайн доступен сразу под названием: «Сегодня», «Завтра», дата
и время. «Когда заняться» задаётся отдельно; можно оставить только срок.
Общий список доступен через «Все даты». Подробности — [docs/DAY_PLANNER.md](docs/DAY_PLANNER.md).

## Защита разделов

Настройки → «Код-пароль на разделы»: шестизначный код на выбранные разделы,
повторная блокировка при уходе в фон, смена/отключение с текущим кодом.
Границы защиты и проверки — [docs/SECTION_LOCKS.md](docs/SECTION_LOCKS.md).

## Темы и иконка

В настройках доступны светлые Lifety Light, Catppuccin Latte, Gruvbox Light
и тёмные Lifety, Tokyo Night, Catppuccin Mocha, Gruvbox Dark, Nord.
Тема меняется сразу и сохраняется на устройстве. Кнопка «Применить иконку …»
отдельно меняет системный значок на вариант в цвет темы; есть возврат к исходному.
Пункт финансов из общих настроек убран. Подробнее — [docs/APPEARANCE.md](docs/APPEARANCE.md).

## Цели и план из ChatGPT

Главная стала компактнее: показатели парами, цели в горизонтальной ленте, короткий
план дня. «Глобальные цели → Все» позволяет добавлять цель, описание и срок.

**Ассистент** теперь открывает ручной обмен без API: «Подготовить первый запрос» /
«Подготовить обновление», копирование в один постоянный чат, отметка отправки и
«Вставить ответ ChatGPT» с предпросмотром. Запрос и ожидание переживают перезапуск.
Настройки обмена: постоянные предпочтения, необязательная личная ссылка на разговор,
сон по выбору — 7 дней плюс медианы по каждому месяцу, без полной истории.
Новый план может оставлять дела в очереди с причиной; дедлайны сохраняются.
Встроенный API-чат доступен через дополнительные настройки. Автоматической
отправки и синхронизации аккаунта нет. Формат v2, совместимость v1, проверки
и ограничения — [docs/CHATGPT_PLANNING.md](docs/CHATGPT_PLANNING.md).

## Люди и дни рождения

**План → Люди**: ФИО, день рождения с необязательным годом, интересы, увлечения
и идеи подарков отдельными списками. Пункты можно добавлять, редактировать,
удалять и вставлять сразу несколькими строками. На iPhone дата рождения
выбирается нативными колёсиками с вариантом «Без года». Есть поиск и удаление
карточки с подтверждением. Свои разделы со списками и фотография описаны выше.
Дни рождения автоматически повторяются во встроенном календаре; касание события
открывает карточку. Для 29 февраля в обычные годы используется 28 февраля.
Всё хранится локально и переживает перезапуск. Подробнее — [docs/PEOPLE.md](docs/PEOPLE.md).

## Свои категории расходов

Финансы → **Категории**: добавить категорию, изменить название, выбрать цвет из
палитры или задать HEX, скрыть/вернуть. Изменения сохраняются в SQLite после
перезапуска. Старые расходы связаны с постоянным ID: переименование и скрытие
не меняют суммы и историю. Подробности — [docs/EXPENSE_CATEGORIES.md](docs/EXPENSE_CATEGORIES.md).

## Что работает

- Создание, редактирование, удаление и выполнение задач. Фильтры актуальных,
  завершённых и всех задач; состояния загрузки, пустого списка и ошибки.
- Необязательные план и дедлайн с выбором даты и 24-часового времени. Даты
  сохраняются в UTC epoch milliseconds, показываются в часовом поясе устройства.
- Месячный календарь с понедельника, переключение месяцев, выбор дня, индикаторы
  задач и переход в общий редактор. Учитывает и план, и дедлайн; совпадающие даты
  не дублируют задачу. Создание из календаря предлагает выбранный день в 09:00.
- Material 3 в неоновой тёмной палитре, карточки 16–20 dp, общие иконки и
  анимированный маскот Lifety. Задачи и расходы редактируются в ModalBottomSheet.
- Пять разделов: Главная, План, Финансы, Здоровье, Ассистент. Задачи, календарь и люди
  переключаются внутри Плана; настройки доступны в верхней панели.
- Finance: ручное добавление/редактирование/удаление расходов, день/неделя/месяц,
  валюта, категории с диаграммами и сводки, месячный лимит в сессии экрана. Банковские уведомления сохраняются
  только после подтверждения. Демо-уведомление работает без системного доступа.
- Главная: сегодняшние задачи и дедлайны, суммы текущего месяца отдельно по валютам,
  последние показатели здоровья. Без стартовых вымышленных данных.
- Здоровье: вся локальная история сна и шагов, графики за 7/30 дней и всё время,
  средние по дням с данными, выбор дня и список записей. Ручной ввод — с датой.
  Android 9+ предлагает явный импорт дневных итогов Health Connect за 30 дней
  с предпросмотром и подтверждением. Пропуски не превращаются в нули, повторные
  накопительные замеры не складываются. Подробности — [docs/HEALTH_HISTORY.md](docs/HEALTH_HISTORY.md).
- Assistant: чат с native agent runtime, выбор доступа к данным, локальная память
  и сохранённые разборы. Нужны свои ключ и модель. Доступ выключен по умолчанию;
  запросы запускаются сообщением, изменения применяются после подтверждения.
  Android хранит явно сохранённый ключ через Keystore.
- Android notification listener зарегистрирован в Manifest. Чтение выбранных
  банков включается пользователем через отдельное системное разрешение.
- `TaskRepository`, `ExpenseRepository`, `HealthRepository` сохраняют данные
  в `lifety.db`; SQLDelight Flow уведомляет подписчиков после записи.
- Android Application владеет одним контейнером БД, общим для всех экранов и
  Activity. iOS entry point также подключает persistent-контейнер. Диск работает
  на IO, а не на UI-потоке. При ошибке нет скрытой подмены на in-memory storage.
- NavHost сохраняет верхние routes, back stack и экранные состояния;
  редактор получает id задачи через typed route, Back возвращает к источнику.

Модели и прежние методы репозиториев сохранены. HealthRepository дополнен
`observeAll()` и `updateAll()` с совместимыми реализациями по умолчанию. Формат
прежних SQLite-таблиц сохранён. Для пользовательских категорий схема обновлена до
версии 2: `1.sqm` добавляет только `expenseCategory`, сохраняя прежние данные.
Нет сервера, регистрации, облачной БД, Firebase и внешних календарей.
Health Connect подключён только на Android 9+: дневные итоги сна и шагов
за последние 30 календарных дней, включая текущий неполный день. На iOS аналогичный
явный импорт реализован через HealthKit. Фонового импорта на обеих платформах нет. Особенности SDK и проверок — в
`shared/src/commonMain/kotlin/ru/lifety/feature/health/INTEGRATION.md`.
Planner P1 (напоминания о дедлайнах, повторы, приоритеты, длительность) не реализован.

## Структура кода

Два Gradle-модуля: `:androidApp` и `:shared`. Основные файлы:

```text
androidApp/src/main/
  AndroidManifest.xml                         LifetyApplication registration
  kotlin/ru/lifety/android/
    LifetyApplication.kt                      Process-wide DB container
    MainActivity.kt                           Bootstrap + root Compose
shared/src/commonMain/kotlin/ru/lifety/
  app/
    LifetyApp.kt                              NavHost, five primary sections
    PlanScreen.kt                             Tasks / Calendar tabs
    LifetySettingsSheet.kt                     Theme info + Finance entry
    RepositoryBootstrap.kt                    Loading/error/retry
    navigation/{AppDestination,RootNavigator,TaskEditorRoute}.kt
  core/
    di/AppContainer.kt                        Driver + all repositories
    model/{Task,Expense,HealthSnapshot}.kt      Unchanged
    repository/*.kt                           Stable methods + health history API
    navigation/PlannerNavigation.kt           Editor integration point
    planner/TaskListViewModel.kt               Shared StateFlow state
    time/PlannerDates.kt                      KMP date/time and day selection
    ui/                                       Theme, tokens, mascot, icons,
                                              date/empty/task components
  database/
    DatabaseDriverFactory.kt                  SQLDelight Int adapters
    SqlDelightRepositories.kt                 Three persistent repositories
  feature/
    tasks/{TasksScreen,TaskEditorScreen,TaskEditorViewModel}.kt
    calendar/CalendarScreen.kt
    finance/                                  Expenses + confirmed imports
    dashboard/, assistant/, health/            Local overview, opt-in AI, health
shared/src/commonMain/sqldelight/ru/lifety/database/Lifety.sq
shared/src/androidMain/                        SQLite/HTTP platform drivers
shared/src/iosMain/                            Drivers + MainViewController
shared/src/commonTest/                         Model, navigation, date tests
shared/src/androidHostTest/                    SQLite schema/persistence tests
```

## Интеграция других агентов

Единый визуальный контракт и API маскота — в
[docs/DESIGN_SYSTEM.md](docs/DESIGN_SYSTEM.md); исходный концепт — в
[docs/design/lifety-reference.png](docs/design/lifety-reference.png).
Цвета и размеры задаются в `core/ui/LifetyDesign.kt`, тема — в `LifetyTheme.kt`.
Агенты используют готовые компоненты; дополнительных функций ради концепта не требуется.

Полные стабильные сигнатуры, пакеты, пути и правила — в
[docs/CONTRACTS.md](docs/CONTRACTS.md). Владение файлами — в [AGENTS.md](AGENTS.md).

Все существующие `@Composable fun XxxScreen(modifier: Modifier = Modifier)`
зарегистрированы в NavHost. Другим агентам достаточно заменить тело своего
экрана; правки root-файла не нужны. Репозитории доступны через
`ru.lifety.core.ui.LocalRepositories.current` (`tasks`, `expenses`, `health`).
Production entry points передают реальный набор; `null` оставлен для preview.

Открытие редактора из любого feature без импорта tasks:
`LocalPlannerNavigation.current?.openTask(task.id)` или `.createTask()` из
`ru.lifety.core.navigation`. Верхние переходы доступны через
`LocalRootNavigator.current?.navigate(AppDestination.CALENDAR)` из
`ru.lifety.app.navigation`.

Codex 2 владеет Finance/Notifications; Codex 3 — Dashboard/Assistant/Health/AI.
Новые зависимости и изменения Manifest/схемы/routes согласуются с Codex 1.
Все опубликованные экраны уже подключены к общим репозиториям.
AI требует пользовательского ключа/модели, остальные функции работают локально.
Finance listener уже зарегистрирован. Он не требует репозитория в сервисе:
публикует неподтверждённые предложения в общую очередь, а экран сохраняет
подтверждённые расходы через `LocalRepositories.current.expenses`.
Самостоятельное включение notification access не выполняется.

## Проверки

Обновление ассистента, защиты разделов и подробного расписания: Android build/lint,
352 успешных теста на JVM/iOS, Xcode simulator build и ручная проверка интерфейса
iPhone. Точные сценарии и ограничения — [docs/SECTIONS_SCHEDULE_CHECK.md](docs/SECTIONS_SCHEDULE_CHECK.md).

Проверено **25 октября 2026**, macOS arm64, JDK **21.0.12.1**, Gradle **9.5.1**,
Android SDK **37**, Build Tools **36.0.0**, Xcode **27.1**.
Команды выполнены с указанными выше `JAVA_HOME` и `ANDROID_HOME`:

```sh
./gradlew :androidApp:assembleDebug :shared:testAndroidHostTest
./gradlew :androidApp:build :shared:testAndroidHostTest
./gradlew :shared:compileKotlinIosSimulatorArm64 :androidApp:build :shared:testAndroidHostTest
```

Обновление истории здоровья: Android build/lint, iOS Simulator arm64 и common
metadata скомпилированы; **135 тестов прошли, 3 live LLM tests пропущены** без ключей.
Команда и ограничения — [docs/HEALTH_HISTORY.md](docs/HEALTH_HISTORY.md).

После интеграции Health Connect (`14a4311`) повторена полная команда Android build,
host tests и iOS compile: **92 теста без failures/errors/skipped**.
Проверка слияния и ограничения SDK — [docs/ASSISTANT_MERGE_REVIEW.md](docs/ASSISTANT_MERGE_REVIEW.md).
Следующие результаты описывают предыдущую проверку до этого обновления.

- Android debug/release assembly и lint — **BUILD SUCCESSFUL**.
- **71 host tests: 0 failures, 0 errors, 0 skipped**: Core/Planner, Finance/Notifications,
  Dashboard/Health и AI-контракты/приватность/HTTP fixtures.
- Проверены реактивные insert/update/delete, полная замена nullable полей,
  стабильная сортировка, latest health по максимальному timestamp,
  точность денег >2⁵³, закрытие/повторное открытие файловой SQLite для всех
  трёх репозиториев и сохранение удаления при следующем открытии.
- Проверены локальная дата относительно UTC, civil date DatePicker,
  отсутствие дублей в календаре, leap day, DST gap detection.
- Общий код, платформенный DI и UI скомпилированы для **iOS Simulator arm64**.

Остаются предупреждения beta expect/actual classes и рекомендации lint по
обновлению фиксированных зависимостей, data extraction rules. Launcher mark и системный splash обновлены под Lifety.
Release APK unsigned; публикационная подпись не настраивалась.

После объединения выполнен smoke test на временном эмуляторе **Android 15 API 35,
arm64**: установка и холодный запуск APK, создание задачи с датой, открытие и
редактирование из календаря, сохранение ручного расхода 125,50 RUB,
подтверждение демо-уведомления 349,90 RUB, проверка итога 475,40 RUB.
Затем процесс принудительно остановлен и запущен снова: задача и оба расхода
восстановились в интерфейсе; содержимое реальной Android SQLite также проверено.
Ошибок AndroidRuntime не обнаружено.

После редизайна повторены полная Android build/lint, все 34 host tests и компиляция
общего UI для iOS Simulator arm64. На Android API 35 проверены пять пунктов,
вкладки Плана, сохранение задачи и расхода через нижние листы, DatePicker и
доступность сохранения при открытой клавиатуре. Дизайн-контракт фиксирован в
`docs/DESIGN_SYSTEM.md`; модели и схема БД не менялись.

После нового объединения опубликованных веток повторены Android build/lint,
71 host test и iOS Simulator arm64 compile. Разрешены конфликты Finance UI:
новая аналитика работает с существующими неоновой темой, FAB, DatePicker и sheets.
В трёх iOS-адаптерах третьего агента исправлены фабрики NSDate.
На Android API 35 проверены Dashboard с реальной задачей, сводки и месячный лимит
Finance, запись сна 7 ч 30 мин/8000 шагов и восстановление после остановки процесса,
AI-предпросмотр с разрешёнными только задачами без отправки в сеть.
Локальная `feature/assistant` (`e63b63c`) расходится с опубликованной `e30a688`:
её альтернативный mock-AI/root DI не смешивались с опубликованным opt-in API.

Не проверены физический Android, API 24–25 runtime, реальный банковский push и
системный notification access, реальный запрос к AI-провайдеру с ключом,
iOS framework linking/runtime/device arm64.
Несохранённые черновики и неподтверждённые notification suggestions могут теряться
после завершения процесса; сохранённые данные остаются в SQLite.

Для ручного smoke test на устройстве: создать задачу с планом и дедлайном,
изменить её из календаря, отметить выполнение, проверить фильтры, перезапустить
приложение и проверить данные, удалить задачу и повторить запуск.
