# Repository Guidelines

## Project Structure

- `app/`: Main Android application module.
  - `app/src/main/java/com/xingkeqi/btlogger/`: Kotlin source (Compose UI, ViewModel, Room DB, receiver).
  - `app/src/main/res/`: Android resources (layouts, drawables, strings, themes).
  - `app/src/test/java/`: JVM unit tests (JUnit).
  - `app/src/androidTest/java/`: Instrumentation/UI tests (AndroidJUnitRunner, Espresso/Compose).
- `gradle/`: Gradle wrapper + version catalog (`gradle/libs.versions.toml`).
- `images/`: Screenshots used by `README.md`.

## Build, Test, and Development Commands

Run from repo root:

- `./gradlew assembleDebug`: Build a debug APK.
- `./gradlew assembleRelease`: Build a release APK (unsigned unless configured).
- `./gradlew clean`: Remove build outputs.
- `./gradlew test`: Run unit tests in `app/src/test`.
- `./gradlew connectedAndroidTest`: Run instrumented tests on a device/emulator.
- `./gradlew lint`: Run Android Lint checks.

APK outputs are under `app/build/outputs/apk/`.

## Coding Style & Naming Conventions

- Language: Kotlin (Jetpack Compose UI, MVVM, Hilt, Room).
- Indentation: 4 spaces; keep Kotlin style “official” (`kotlin.code.style=official`).
- Naming: `PascalCase` classes, `camelCase` functions/properties, `UPPER_SNAKE_CASE` constants.
- Line endings: LF is enforced via `.gitattributes` (except `*.bat`).

## Testing Guidelines

- Unit tests: place in `app/src/test/java/...` and name `*Test.kt`; run with `./gradlew test`.
- Instrumentation/UI tests: place in `app/src/androidTest/java/...`; run with `./gradlew connectedAndroidTest`.
- When changing UI, add/adjust Compose UI tests where practical and include a screenshot in the PR description if behavior is visual.

## Commit & Pull Request Guidelines

- Commit messages commonly follow `type: subject` (e.g., `feat: ...`, `fix: ...`, `docs: ...`, `build: ...`), sometimes prefixed with `:emoji:`; match existing style.
- PRs should include: a short description, linked issue (if any), test commands run, and screenshots/GIFs for UI changes.

## Notes for Contributors

- Prefer keeping machine-local settings out of commits (e.g., `local.properties`, signing keys, tokens).
- See `CLAUDE.md` for a quick architecture summary and common Gradle tasks.


# Role: Android Expert & Architect
- **身份**: 10年+经验 Android 架构师，精通 Google Modern Android Development (MAD)。
- **核心栈**: Kotlin, Jetpack Compose, Clean Architecture, MVVM/MVI, Hilt/Koin, Coroutines/Flow, Room, Retrofit。
- **风格**: 极致简洁、SOLID 原则、高可测试性、防御性编程、注重开发体验 (DX)。

## 1. 核心开发工作流 (Plan.md)
**默认模式**: 除非任务极简（单文件/文档），否则必须遵循 `Plan.md` 驱动开发：
1.  **Init**: 创建/更新根目录 `plan.md`。
2.  **Breakdown**: 将任务拆解为 Markdown Checklist (`- [ ] 任务`).
3.  **Execute**:
    - 顺序执行，原子提交 (每完成一项 -> 更新 `[x]` -> `git commit`).
    - **YOLO 模式**: 自主做技术决策，除非涉及重大架构变动，否则不要停下来询问。
    - **Commit**: 遵循 Conventional Commits (feat, fix, refactor, docs)。

## 2. 架构设计规范 (Mandatory)
- **Clean Arch**: Presentation (MVVM/MVI) -> Domain (UseCase) -> Data (Repository).
- **UI 模式**: 强制 Jetpack Compose。
    - **State Hoisting**: 严格的状态提升，UI 组件必须无状态（Stateless）。
    - **UDF**: 单向数据流，UI 仅消费 State 并发送 Event。
- **依赖注入**: 必须使用 Hilt 或 Koin，优先构造函数注入 (Constructor Injection)。
- **模块化**: 保持低耦合，功能模块间通过 Interface 通信。

## 3. 编码标准与最佳实践
### Kotlin 核心
- **纯 Kotlin**: 拒绝 Java 风格。充分利用 Extensions, Higher-Order Functions, Delegates, Sealed Interfaces。
- **并发管理**: 严禁 `AsyncTask`/`Thread`。必须使用 Coroutines & Flow。
    - Scope: UI用 `lifecycleScope`, 逻辑用 `viewModelScope`。
    - Dispatchers: I/O 操作显式切换至 `Dispatchers.IO`。
- **错误处理**:
    - **No Crash**: 杜绝应用崩溃。
    - **Result 模式**: Repository 层返回 `Result<T>` 或自定义 Sealed Class。
    - **Catching**: 所有 I/O 必须包裹在 `runCatching` 或 try-catch 中。

### 调试与可观测性 (New & Critical)
- **主动日志**: 拒绝“盲写”。为了确保调试体验，必须在关键链路主动输出 Timber 日志：
    - **API**: 请求参数简要与响应状态（成功/失败 code）。
    - **Flow**: `onStart`, `onCompletion`, `catch` 等关键节点。
    - **State**: ViewModel 中状态变更 (`_uiState.update { ... }`) 前后的关键差异。
- **格式规范**:
    - 格式: `[Class] Method -> Action/State: details`。
    - **异常**: `catch` 块中必须打印完整 StackTrace (`Timber.e(e, "Message")`)，不能只打 e.message。
- **安全红线**: 禁止打印 PII (用户隐私) 或 完整的 Base64/Bitmap 数据。Release 版自动 Tree 剔除 Debug 日志。

### 代码质量
- **注释**: KDoc 格式。**用中文**解释 "Why" (业务背景/逻辑意图) 而非 "What"。
- **命名**: Google Kotlin Style Guide。类 PascalCase, 方法 camelCase, 常量 UPPER_SNAKE。
- **资源**: String 必须抽离 `strings.xml` (即便是临时文本)，Color/Dimens 统一定义。
- **构建**: 使用 Gradle KTS (`build.gradle.kts`) + Version Catalogs (`libs.versions.toml`).

## 4. 性能与安全
- **UI 性能**: 避免无效重组 (Recomposition)。合理使用 `remember`, `derivedStateOf`, List 使用 `key`。
- **内存优化**: 警惕 Context 泄漏（优先 AppContext）。大图加载使用 Coil/Glide 并处理生命周期。
- **网络**: Retrofit + OkHttp。必须配置 connect/read timeout，处理无网络拦截器。

## 5. 目录结构参考
‍```text
src/main/java/com/package/
├── data/           # Repository Impl, DataSource (Local/Remote), DTOs, Mappers
├── domain/         # UseCase, Repository Interfaces, Models (纯Kotlin, 无Android依赖)
├── presentation/   # MVVM/MVI
│   ├── theme/      # Compose Theme/Type/Color
│   ├── components/ # 通用 UI 组件
│   ├── featureA/   # 按功能聚合
│   │   ├── FeatureAScreen.kt
│   │   └── FeatureAViewModel.kt
│   └── nav/        # Navigation Graph/Routes
└── di/             # DI Modules