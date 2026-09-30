package xyz.nulldev.ts.config

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

const val CONFIG_PREFIX = "suwayomi.tachidesk.config"

/**
 * 沙盒里所有 Android 目录（`androidcompat.rootDir` → `AndroidFiles` 的
 * dataDir / filesDir / databasesDir / prefsDir …）的**唯一根**。
 *
 * 取值顺序：
 * 1. `-Dsuwayomi.tachidesk.config.server.rootDir=<路径>`（Rust 侧 spawn 本进程时的精确覆盖）；
 * 2. 环境变量 `SUWAYOMI_APPDATA_DIR`（Rust 侧 `spawn_java` 已经传的就是这个）；
 * 3. 字面量 `appdata` —— 与 [Main.kt 的默认值]一致。
 *
 * 原来第 3 档是 `ca.gosyer:kotlin-multiplatform-appdirs` 推导的平台用户数据目录
 * （Windows 下 `%APPDATA%\Tachidesk`）。那会让 `AndroidFiles` 的 15 个目录落到**另一个根**，
 * 与 `SUWAYOMI_APPDATA_DIR` 派生的扩展目录 / dex2jar 产物目录 / 设置目录分裂成两处 ——
 * 扩展用 `context.filesDir`、`context.getDatabasePath()` 写的东西与 Rust 侧看到的不在同一棵树下。
 * 现在整棵树只由 Rust 侧那一个旋钮决定，顺带去掉 appdirs 依赖。
 */
val ApplicationRootDir: String
    get(): String =
        System.getProperty("$CONFIG_PREFIX.server.rootDir")
            ?: System.getenv("SUWAYOMI_APPDATA_DIR")
            ?: "appdata"
