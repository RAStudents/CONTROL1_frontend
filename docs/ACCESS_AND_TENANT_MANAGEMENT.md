# Документация: Управление доступом и контекстом тенанта (`:core:access`)

Данный документ подробно описывает архитектуру, механизмы многопоточности, модель состояний и политики безопасности модуля **`:core:access`** в проекте **CONTROL** (Kotlin Multiplatform: Android & Kotlin/Wasm).

---

## 1. Архитектурный обзор

Модуль `:core:access` отвечает за двухконтурное управление правами и изолированными контекстами тенантов (компаний):
1. **Глобальный контекст пользователя (`GlobalUserContext`)**: профиль, глобальные права (системный администратор, биллинг-менеджер), доступные компании.
2. **Контекст тенанта (`TenantContextState`)**: активная компания, ролевые права тенанта, происхождение разрешений (`AccessOrigin`), статус ограничений компании (`AccessRestriction`) и активные оверрайды техподдержки (`SupportGrantInfo`).

Архитектура строго следует принципам **Clean Architecture** и мультиплатформенной безопасности **Fail-Closed**:

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│                             UI / Presentation Layer                         │
│   (AppNavHost, TenantRouteGuard, TenantPicker, PermissionGate, Banners)     │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │
                      UseCases (Domain API Фасад)
                                       │
┌──────────────────────────────────────▼──────────────────────────────────────┐
│                            Domain Layer (:core:access)                      │
│                                                                             │
│  ┌────────────────────────┐                   ┌──────────────────────────┐  │
│  │     AccessManager      │                   │ TenantContextCoordinator │  │
│  │ (GlobalUserContext,    │                   │ (Active Tenant State,    │  │
│  │  Generations, Mutex)   │                   │  Epochs, SingleFlight)   │  │
│  └───────────┬────────────┘                   └─────────────┬────────────┘  │
│              │                                              │               │
│              ▼                                              ▼               │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                     AccessInvalidationCoordinator                     │  │
│  │         (Single-flight 403 Handler, Owner Token, Pending Queue)       │  │
│  └───────────────────────────────────┬───────────────────────────────────┘  │
└──────────────────────────────────────┼──────────────────────────────────────┘
                                       │
┌──────────────────────────────────────▼──────────────────────────────────────┐
│                             Data Layer (:core:access)                       │
│                                                                             │
│  ┌─────────────────────────────────┐      ┌──────────────────────────────┐  │
│  │       AccessRepositoryImpl      │      │   AccessLocalDataSource      │  │
│  │ (Fail-Closed ETag Cache + HTTP) │◄────►│ (InMemory: ETag, Snapshots)  │  │
│  └─────────────────┬───────────────┘      └──────────────────────────────┘  │
│                    │                                                        │
│                    ▼                                                        │
│  ┌───────────────────────────────────┐                                      │
│  │       AccessRemoteDataSource      │                                      │
│  │ (Ktor Client, Fail-Closed DTOs)   │                                      │
│  └─────────────────┬─────────────────┘                                      │
└────────────────────┼────────────────────────────────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────────────────────────────────┐
│                        Network Layer (:core:network)                        │
│             SafeRequest, SessionManager, AccessInvalidationDispatcher       │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Модель состояний (State Machine)

### 2.1. Глобальный доступ: `GlobalAccessState`

Отражает состояние загрузки и валидности профиля авторизованного пользователя:
* [`GlobalAccessState.InitialLoading`](../core/access/src/commonMain/kotlin/studio/acmenra/control/core/access/domain/model/AccessState.kt): начальное состояние до завершения первой синхронизации профиля.
* [`GlobalAccessState.Ready`](../core/access/src/commonMain/kotlin/studio/acmenra/control/core/access/domain/model/AccessState.kt): профиль загружен. Содержит `context: GlobalUserContext` и флаг фонового обновления `isRefreshing: Boolean`.
* [`GlobalAccessState.Restricted.AccountFrozen`](../core/access/src/commonMain/kotlin/studio/acmenra/control/core/access/domain/model/AccessState.kt): аккаунт заморожен на глобальном уровне (код ошибки `ACCOUNT_FROZEN`, например, задолженность по биллингу `BILLING_DEBT`). Содержит объект `restriction: AccessRestriction`.
* [`GlobalAccessState.Error`](../core/access/src/commonMain/kotlin/studio/acmenra/control/core/access/domain/model/AccessState.kt): сетевая ошибка загрузки глобального контекста. Содержит `error: NetworkError`.

### 2.2. Контекст тенанта: `TenantContextState`

Определяет текущий уровень допуска пользователя к конкретной компании:
* [`TenantContextState.NotSelected`](../core/access/src/commonMain/kotlin/studio/acmenra/control/core/access/domain/model/AccessState.kt): компания не выбрана (стартовое состояние или состояние пользователя без компаний).
* [`TenantContextState.Loading`](../core/access/src/commonMain/kotlin/studio/acmenra/control/core/access/domain/model/AccessState.kt): переходное состояние загрузки при выборе компании `requestedCompanyId`.
* [`TenantContextState.Authorized`](../core/access/src/commonMain/kotlin/studio/acmenra/control/core/access/domain/model/AccessState.kt): успешная авторизация в тенанте. Содержит `context: AuthorizedTenantContext` и флаг `isRefreshing: Boolean`.
* [`TenantContextState.Restricted`](../core/access/src/commonMain/kotlin/studio/acmenra/control/core/access/domain/model/AccessState.kt): доступ ограничен на уровне тенанта. Подтипы:
  * `CompanyFrozen`: компания заблокирована/заморожена администратором или платформой. Сохраняет `restriction: AccessRestriction?` даже после истечения override-гранта.
  * `NoPermissions`: у пользователя нет активных прав в компании.
  * `AccessDenied`: доступ к компании отклонён или отозван.
* [`TenantContextState.Error`](../core/access/src/commonMain/kotlin/studio/acmenra/control/core/access/domain/model/AccessState.kt): сетевой сбой при загрузке доступа тенанта. Позволяет выполнить повтор по кнопке «Повторить» (`Retry`).

---

## 3. Модель разрешений и происхождения прав (Fail-Closed)

### 3.1. Ключи разрешений (`PermissionKey`)

Разрешения строго типизированы через sealed-интерфейс [`PermissionKey`](../core/access/src/commonMain/kotlin/studio/acmenra/control/core/access/domain/model/Permission.kt):
* `PermissionKey.Tenant(val permission: TenantPermission)`: права внутри компании (`EVENTS_READ`, `DEVICES_READ`, `DEVICES_CREATE`, `DEVICES_UPDATE`, `DEVICES_DELETE`, `REPORTS_READ`, `REPORTS_EXPORT`, `DICTIONARIES_READ`, `DICTIONARIES_MANAGE`, `AUDIT_READ`, `USERS_READ`, `USERS_MANAGE`).
* `PermissionKey.Global(val permission: GlobalPermission)`: глобальные системные права (`COMPANIES_MONITOR`, `COMPANIES_MANAGE`, `GLOBAL_USERS_MANAGE`).
* `PermissionKey.Support(val permission: SupportPermission)`: права технической поддержки (`DEVICE_DIAGNOSTICS`, `DEVICE_RESTART`, `TOKEN_ROTATE`).

### 3.2. Источники происхождения прав (`AccessOrigin`)

Каждое право мапится на источники, из которых оно получено:
* `DIRECT_MEMBERSHIP`: прямое членство в штате компании.
* `VENDOR_GLOBAL`: глобальные права вендора платформы.
* `SUPPORT_GRANT`: временное делегирование прав через заявку в техподдержку.
* `UNKNOWN`: неизвестный бэкенд-источник (Fail-Closed игнорирование).

Если бэкенд возвращает неизвестное право или неизвестный origin, кастомный десериализатор [`SafeAccessSerializers.kt`](../core/access/src/commonMain/kotlin/studio/acmenra/control/core/access/data/serializer/SafeAccessSerializers.kt) игнорирует неизвестные элементы, исключая падение приложения и соблюдая принцип минимальных привилегий (Fail-Closed).

---

## 4. Конкурентность и потокобезопасность

Модуль спроектирован для надежной работы в конкурентной среде с параллельными сетевыми запросами, сменой тенантов и фоновыми инвалидациями.

### 4.1. Двухуровневое версионирование запросов

В [`TenantContextCoordinator.kt`](../core/access/src/commonMain/kotlin/studio/acmenra/control/core/access/domain/TenantContextCoordinator.kt) используется два уровня барьеров:
1. **`globalTenantEpoch: Long`**:
   Глобальный барьер эпохи тенантов. Инкрементируется при сбросе контекста (`reset()`, например при логауте). Любой сетевой запрос, начатый в предыдущей эпохе, отбрасывается при завершении, предотвращая гонку со сменой пользователя.
2. **`companyGenerations: MutableMap<String, Long>`**:
   Поколение запросов конкретной компании. Инкрементируется при каждом запуске `selectTenant(companyId)` или `refreshTenant(companyId)`. Гарантирует, что более старый ответ для компании $A$ не перезапишет более свежий ответ той же компании. При этом запросы разных компаний ($A$ и $B$) не инвалидируют друг друга.

### 4.2. Безопасность завершения в `finally` (Job Identity)

При быстрой смене компаний ($A_1 \to B \to A_2$) отменяемая корутина старого выбора не должна затирать активную ссылку нового выбора.

* В **`selectTenant`**:
  ```kotlin
  val currentJob = coroutineContext[Job]
  try {
      doSelectTenant(companyId, generation, requestEpoch, requestSessionRevision)
  } finally {
      withContext(NonCancellable) {
          mutex.withLock {
              if (activeSelectionJob === currentJob) {
                  activeSelectionCompanyId = null
                  activeSelectionJob = null
              }
          }
      }
  }
  ```
  Фиксация `val currentJob = coroutineContext[Job]` до входа в `finally { withContext(NonCancellable) { ... } }` гарантирует корректную проверку идентичности, поскольку корутинный контекст внутри `withContext` содержит собственный дочерний `Job`.

* В **`refreshTenant`**:
  ```kotlin
  val currentJob = coroutineContext[Job]
  try {
      doRefreshTenant(companyId, generation, requestEpoch, sessionRevision, force)
  } finally {
      withContext(NonCancellable) {
          mutex.withLock {
              if (activeRefreshJobs[companyId]?.job === currentJob) {
                  activeRefreshJobs.remove(companyId)
                  val latest = _tenantState.value
                  if (latest is TenantContextState.Authorized && latest.context.companyId == companyId) {
                      setTenantStateLocked(latest.copy(isRefreshing = false), sessionRevision)
                  }
              }
          }
      }
  }
  ```

### 4.3. Приоритет принудительного обновления (`force = true`)

В [`AccessManager.kt`](../core/access/src/commonMain/kotlin/studio/acmenra/control/core/access/domain/AccessManager.kt) и [`TenantContextCoordinator.kt`](../core/access/src/commonMain/kotlin/studio/acmenra/control/core/access/domain/TenantContextCoordinator.kt) запрос с `force = true` обладает наивысшим приоритетом:
* При поступлении `force = true` поверх выполняющегося фонового `force = false`:
  1. Старая задача немедленно отменяется (`existing.cancel()`).
  2. Запускается свежий сетевой вызов с генерацией нового поколения.
* Запрос `force = true` может коалесцироваться (дедуплицироваться) только с другим `force = true`, предотвращая возврат устаревших кэшированных данных.

### 4.4. Дедупликация и защита от гонок в `AccessInvalidationCoordinator`

Когда сетевой запрос возвращает HTTP 403, срабатывает слушатель `onAccessInvalidated()`.

Координатор инвалидации решает ключевые проблемы:
1. **Пропуск для неавторизованных сессий:** если сессия пользователя не `Authenticated` (например, 403 при неверном логине), инвалидация не производит лишних сетевых вызовов.
2. **Single-flight:** одновременные инвалидации для одного таргета (компании или глобального контекста) схлопываются, предотвращая шквал повторных запросов.
3. **Очередь повторной обработки (`pendingRetryTargets`):** если во время выполнения инвалидации компании или глобального контекста (`ACCOUNT_FROZEN`) приходит повторный сигнал 403, он не отбрасывается, а помещается в очередь и выполняется сразу после завершения текущего прохода.
4. **Защита от Stale Finally (`ownerToken`):**
   Каждая операция генерирует уникальный токен `ownerToken = Any()`. Снятие блокировки `inProgressTargets` в `finally` происходит **только при совпадении владельца**:
   ```kotlin
   if (inProgressTargets[targetKey] === myToken) {
       inProgressTargets.remove(targetKey)
       pendingRetryTargets.remove(targetKey)
   }
   ```
   Это полностью исключает гонки между последовательными операциями инвалидации.

---

## 5. Сетевое взаимодействие и кэширование (HTTP 304 / 403)

### 5.1. Кэширование с HTTP 304 Not Modified

Модуль данных [`AccessRepositoryImpl`](../core/access/src/commonMain/kotlin/studio/acmenra/control/core/access/data/repository/AccessRepositoryImpl.kt) реализует условные запросы по спецификации RFC 9110:
1. Перед отправкой запроса фиксируется временная метка `timeSource.markNow()` для строгого Fail-Closed учета времени сети в TTL грантов.
2. При успешном ответе (HTTP 200) снимок прав сохраняется в [`AccessLocalDataSource`](../core/access/src/commonMain/kotlin/studio/acmenra/control/core/access/data/datasource/AccessLocalDataSource.kt) вместе с заголовком `ETag`.
3. При повторном запросе отправляется заголовок `If-None-Match: <ETag>`. При активном `SupportGrant` заголовок `If-None-Match` не передается, гарантируя получение свежего серверного `remaining_ttl_sec`.
4. При получении **HTTP 304 Not Modified**:
   - Тело ответа не передается по сети, экономя трафик.
   - Возвращается кэшированный снимок `CachedCompanyAccess`.
   - Если локальный кэш отсутствует, выполняется прозрачный повторный запрос без `If-None-Match`.
5. При получении **HTTP 403 Forbidden**:
   - Кэш для компании немедленно удаляется из локального хранилища (`localDataSource.remove(userId, companyId)`), гарантируя невозможность использования устаревших прав после отзыва доступа.

### 5.2. Строгий парсинг URL тенанта в `SafeRequest.kt`

При перехвате HTTP 403 в Ktor-клиенте вызывается обработчик ошибок.
Для надежного извлечения `companyId` из URL запроса используется регулярное выражение с поддержкой базовых путей:
```kotlin
private val TENANT_ENDPOINT_REGEX =
    Regex("^(?:.*?/)?(?:api/v\\d+/)?companies/([^/]+)(?:/.*)?$")

private val RESERVED_COMPANY_SEGMENTS =
    setOf("health", "metrics", "status", "check", "ping", "all", "me", "search")
```
Запросы к `/api/v1/companies/{id}/access` и `/api/v1/auth/context` помечаются атрибутом `SkipAccessInvalidationAttributeKey`, что гарантирует защиту от рекурсивных циклов инвалидации даже при наличии префиксов в базовом URL.

---

## 6. Support Grants и локальные таймеры жизни

Когда инженер технической поддержки получает временный доступ к компании клиента, сервер выдает [`SupportGrantInfo`](../core/access/src/commonMain/kotlin/studio/acmenra/control/core/access/domain/model/AccessModels.kt).

### 6.1. Модель гранта и расчет TTL
* `mode`: режим доступа (`READ_ONLY_SUPPORT`, `ENGINEERING_SUPPORT`, `UNKNOWN`).
* `ticketReference`: номер тикета в ServiceDesk / Jira.
* `allowBillingOverride`: флаг, разрешающий доступ даже при наличии финансовой блокировки (`BILLING_DEBT`).
* `remainingTtlSec`: оставшееся время жизни в секундах на момент ответа сервера.
* `fetchedAtMark: TimeMark`: монотонная временная метка отправки запроса.

Локальное истечение гранта вычисляется динамически на клиенте без обращения к часам ОС (устойчиво к переводу времени пользователем):
```kotlin
val isSupportGrantLocallyExpired: Boolean
    get() =
        supportGrant?.let {
            it.runtimeState == SupportGrantRuntimeState.ExpiredLocally || it.isExpiredNow()
        } ?: false
```

### 6.2. Фоновый таймер экспирации

В [`TenantContextCoordinator.kt`](../core/access/src/commonMain/kotlin/studio/acmenra/control/core/access/domain/TenantContextCoordinator.kt):
1. При получении гранта планируется отложенная корутина на время `remainingTtlSec`.
2. По истечении таймера проверяется актуальность ревизии (`tenantRevision`) и идентификатора гранта (`grantId`):
   ```kotlin
   if (current.context.tenantRevision == expectedRevision &&
       current.context.supportGrant?.grantId == expectedGrantId
   ) {
       // Переход в локально истекшее состояние или CompanyFrozen
   }
   ```
   Это предотвращает ситуацию, когда старый таймер с малым TTL преждевременно инвалидирует новый грант с большим сроком действия.
3. Если компания находится в статусе `CompanyStatus.RESTRICTED`, доступ разрешается только при наличии `allowBillingOverride = true` для ограничения `BILLING_DEBT`. По истечении гранта состояние автоматически переходит в `TenantContextState.Restricted.CompanyFrozen` с **сохранением исходного `restriction`**.

---

## 7. Use Cases (Domain API для Presentation слоя)

Модуль предоставляет чистый набор интерфейсов Use Case для использования в ViewModel и Navigation Guards:

| Use Case | Описание | Метод |
|---|---|---|
| [`ObserveGlobalAccessUseCase`](../core/access/src/commonMain/kotlin/studio/acmenra/control/core/access/domain/usecase/ObserveGlobalAccessUseCase.kt) | Реактивный поток `StateFlow<GlobalAccessState>` | `invoke(): StateFlow<GlobalAccessState>` |
| [`ObserveTenantContextUseCase`](../core/access/src/commonMain/kotlin/studio/acmenra/control/core/access/domain/usecase/ObserveTenantContextUseCase.kt) | Реактивный поток `StateFlow<TenantContextState>` | `invoke(): StateFlow<TenantContextState>` |
| [`SelectTenantUseCase`](../core/access/src/commonMain/kotlin/studio/acmenra/control/core/access/domain/usecase/SelectTenantUseCase.kt) | Выбор активной компании и инициализация контекста | `suspend invoke(companyId: String)` |
| [`RefreshTenantUseCase`](../core/access/src/commonMain/kotlin/studio/acmenra/control/core/access/domain/usecase/RefreshTenantUseCase.kt) | Принудительное обновление текущего тенанта | `suspend invoke(force: Boolean = true)` |
| [`CheckPermissionUseCase`](../core/access/src/commonMain/kotlin/studio/acmenra/control/core/access/domain/usecase/CheckPermissionUseCase.kt) | Синхронная проверка прав с учетом Support Grants | `invoke(requirement: RoutePermissionRequirement): Boolean` |

---

## 8. Внедрение зависимостей (Koin)

Конфигурация DI сосредоточена в [`AccessModule.kt`](../core/access/src/commonMain/kotlin/studio/acmenra/control/core/access/di/AccessModule.kt):

```kotlin
// Data слой
val accessDataModule = module {
    single { AccessMapper() }
    single<AccessErrorParser> { JsonAccessErrorParser() }
    single<AccessLocalDataSource> { InMemoryAccessLocalDataSource() }
    single<AccessRemoteDataSource> { KtorAccessRemoteDataSource(httpClient = get(named("api"))) }
    single<AccessRepository> {
        AccessRepositoryImpl(
            remoteDataSource = get(),
            localDataSource = get(),
            mapper = get(),
        )
    }
}

// Domain слой
val accessDomainModule = module {
    single { TenantSingleFlight() }
    single {
        AccessManager(
            accessRepository = get(),
            sessionManager = get(),
            errorParser = get(),
        )
    }
    single {
        TenantContextCoordinator(
            accessRepository = get(),
            accessManager = get(),
            sessionManager = get(),
            errorParser = get(),
            singleFlight = get(),
        )
    }
    single(createdAtStart = true) {
        AccessInvalidationCoordinator(
            accessManager = get(),
            tenantCoordinator = get(),
            sessionManager = get(),
        ).also { coordinator ->
            get<AccessInvalidationDispatcher>().addListener(coordinator)
        }
    }
    factory<ObserveGlobalAccessUseCase> { ObserveGlobalAccessUseCaseImpl(accessManager = get()) }
    factory<ObserveTenantContextUseCase> { ObserveTenantContextUseCaseImpl(coordinator = get()) }
    factory<SelectTenantUseCase> { SelectTenantUseCaseImpl(coordinator = get()) }
    factory<RefreshTenantUseCase> { RefreshTenantUseCaseImpl(coordinator = get()) }
    factory<CheckPermissionUseCase> { CheckPermissionUseCaseImpl(coordinator = get()) }
}

val accessModule = module {
    includes(accessDataModule, accessDomainModule)
}
```

Модуль `accessModule` автоматически подключается в корневой DI граф приложения в `:shared` (`Koin.kt`).
