// 根构建脚本：只声明插件，不应用到根工程
//
// ⚠️ `android.test` 必须和上面两个 AGP 插件一样在这里 `apply false`。
// 它们的 plugin marker 指向**同一个** AGP 构件（`com.android.tools.build:gradle`），
// 根脚本一旦已经把 AGP 放上 classpath（哪怕没 apply），子模块再用带版本的
// `alias(...)` 去请求 `com.android.test`，Gradle 就会报：
//
//     Error resolving plugin [id: 'com.android.test', version: '8.13.2']
//     > The request for this plugin could not be satisfied because the plugin is
//       already on the classpath with an unknown version
//
// 这一条在 `:core:fs` 那些模块上不会出现，因为它们请求的是**另一个**插件 id
// （`com.android.library`），而它本来就已经在根脚本里声明过了。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.kotlin.android) apply false
}
