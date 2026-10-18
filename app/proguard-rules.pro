# ── 密密文件管理 · R8 规则 ────────────────────────────────────────
# 保留行号便于崩溃定位（不引入任何崩溃上报 SDK）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 枚举 values()/valueOf() 反射调用
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# ── 第三方库规则按里程碑追加 ──────────────────────────────────────

# ── M2 · SMB（smbj + jcifs-ng）──────────────────────────────────
#
# smbj 的 SPNEGO / Kerberos 分支引用 JDK 的 GSSAPI（`org.ietf.jgss.*`），
# 而 Android 上**根本没有这个包**。我们只用 NTLM / 匿名认证，不走 Kerberos，
# 所以正确处置是"忽略这些缺类"，而不是去引一个不存在的依赖。
#
# ⚠️ 不加这条的后果不是运行时问题，而是**构建直接失败**：
#     Execution failed for task ':app:minifyReleaseWithR8'
#     > Missing class org.ietf.jgss.GSSCredential (referenced from … SpnegoAuthenticator …)
# 而 `assembleDebug` 不混淆、不做缺类检查，**所以这个坑在 debug 上完全看不出来**
# —— M2 实测就是这样：debug 一路绿灯，到要出发布包时才发现构建不过。
-dontwarn org.ietf.jgss.**
# 同理：smbj 的部分认证路径引用 `javax.security.auth.*`（JAAS），Android 上也不完整。
-dontwarn javax.security.auth.**

# smbj 内部有按类名反射构造的实现（传输层与认证器），保留类名避免被裁剪。
-keep class com.hierynomus.** { *; }
-dontwarn com.hierynomus.**

# jcifs-ng 用 `ServiceLoader` / 反射加载配置提供者与 SMB1 传输实现。
-keep class jcifs.** { *; }
-dontwarn jcifs.**

# ── MBassador 事件总线（smbj 与 jcifs-ng 的**共同依赖**，M5.1 真机事故根因）──
#
# ## 为什么单独列出来
# 它的包名 `net.engio.mbassy` 与 `com.hierynomus.*` / `jcifs.*` **毫无关系**，
# 而 M2 配规则时是按"**库名**"去 keep 的（jcifs、smbj、simple-xml 各一条）——
# 于是这个不在任何一条里的第三方依赖被整片漏掉。三条规则都生效，唯独这条没有。
#
# 依赖树实测（`./gradlew :core:fs-smb:dependencies`）：
#
#     +--- com.hierynomus:smbj:0.15.0
#     |    +--- net.engio:mbassador:1.3.2      ← **smbj 的直接依赖**
#     |    \--- com.hierynomus:asn-one:0.6.0
#     +--- eu.agno3.jcifs:jcifs-ng:2.1.10
#
# **它对两个库都是必需品，所以两条通道会同时断**：
#   · smbj 主选路径 —— `SMBClient` / `Connection` 的事件分发走 MBassador；
#   · jcifs-ng 降级路径 —— `BaseContext(PropertyConfiguration)` 一构造就建事件总线。
# 结果就是 M5.1 真机的现象：**默认设置下 SMB 完全不可用**，
# "测试"失败、进目录报错，而不是"只影响老的 SMB1 设备"。
#
# ## 漏掉的后果（不是"少个功能"，是必然崩）
# MBassador 的消息分发完全靠反射：按注解 `@Handler` 找订阅方法，再反射构造
# `SubscriptionContext` / `MessageHandler` 组成的调用链。R8 不知道这些类的
# 构造函数会被反射调用，于是把它们删的删、改名的改名，运行期报：
#
#     java.lang.RuntimeException: r9.c: The provided handler invocation
#       did not specify the necessary constructor h(SubscriptionContext)
#
# `r9.c` 是 `MessageBusException` 的混淆名、`h` 是被改名的 `MessageHandler`。
# 对用户就是一句天书，**而且只在 release 包上出现** —— debug 不混淆，一切正常。
# 这正是 M4.1 复现脚本用 debug 包跑、结论是"已修好"，到了真机 release 又炸的原因。
#
# ## 为什么不依赖 `-dontwarn` 就够
# `-keep` 保留**类名 + 全部成员**，防止构造函数被删/被改名；`-dontwarn`
# 只是让构建不因缺类告警而失败。MBassador 引用 `javax.el.*`（Java EL 表达式
# 过滤），Android 上不存在该包 —— 但那一组 `dontwarn` 已在上面按 AGP 生成的
# `missing_rules.txt` 逐类列出，这里不需要重复。
#
# ## 体积代价
# MBassador 整个包约 100 个类、DEX 增量实测约 16 KB。换来的是"两条 SMB 通道
# 真的能用"，符合本项目"功能 > 性能 > 体积"的取舍顺序。
-keep class net.engio.mbassy.** { *; }
-dontwarn net.engio.mbassy.**

# ── BouncyCastle · MD4（jcifs-ng 的 NTLM 必需项；M5.1 真机事故的**第二层**）──
#
# ## 现象
# 修完 MBassador 之后，「强制 SMB1」仍然连不上，报：
#
#     java.security.NoSuchAlgorithmException: no such algorithm: MD4 for provider BC
#
# 看起来像"Android 不支持 MD4"，**其实 BC 自己很无辜** —— 它是被 R8 削掉了实现类，
# 而且削完还一声不吭（见下）。
#
# ## 根因：BC 靠**运行期拼出来的字符串**加载算法实现
# jcifs 的 NTLM 要算 NT 哈希 = MD4(password)。它**不自己实现 MD4**，而是向 BC 借：
#
#     jcifs.util.Crypto.getProvider()  →  new org.bouncycastle.jce.provider.BouncyCastleProvider()
#     jcifs.util.Crypto.getMD4()       →  MessageDigest.getInstance("MD4", thatProvider)
#
# 而 BC 的 `BouncyCastleProvider.setup()` **反编译实测**是这么注册算法的：
#
#     ClassUtil.loadClass(BouncyCastleProvider.class, packageName + algorithm + "$Mappings")
#       ├─ 拿到 Class  → newInstance().configure(this)   // 例如 ...digest.MD4$Mappings
#       └─ 拿不到      → **静默返回 null，什么都不报**
#
# 于是"keep 住 BouncyCastleProvider"是**不够的**：`MD4$Mappings` 的引用者是一段
# 运行期拼接的字符串，R8 的可达性分析看不见它，就把整个 MD4 家族删了
# （实测 usage.txt：`org.bouncycastle.jcajce.provider` 下被删 1879 个类）。
# 类没了 → configure 从不执行 → 算法从未注册 → 报"provider BC 里没有 MD4"。
#
# 同一条反射链上还有第二跳：`$Mappings.configure()` 注册实现类**也是字符串拼接**：
#
#     addAlgorithm("MessageDigest.MD4", PREFIX + "$Digest")
#     addHMACAlgorithm(this, "MD4", PREFIX + "$HashMac", PREFIX + "$KeyGenerator")
#
# 所以 `$Digest` / `$HashMac` / `$KeyGenerator` 也不可见，必须一并保留。
# 它们的直接依赖（`BCMessageDigest`、`crypto.digests.MD4Digest`、`DigestAlgorithmProvider`）
# 属于**直接引用**，R8 会顺着保留，不必在这里列。
#
# ## 为什么只 keep MD4，而不是整棵 BC
# jcifs 全程只有 `Crypto` 一处持有 BC provider，且只有两个算法会走它：
#   · `MD4`      —— NTLM 的 NT 哈希，**SMB1/SMB2 认证都要**，必需；
#   · `AESCMAC`  —— 只有 SMB 3.1.1 签名用到。本 App 走 jcifs 时**永远是强制 SMB1**
#                   （`openSmb1` 把 min/maxVersion 都钉在 SMB1），那时 jcifs 走的是
#                   `SMB1SigningDigest`（MD5 + RC4），两个都从**系统默认 provider** 取，
#                   所以 AESCMAC 不会被请求，无需保留。
# 其余算法（MD5 / SHA-512 / RC4 / DES）jcifs 一律 `getInstance(alg)` **不传 provider**，
# 由 Android 自带的 provider 供给，与 BC 无关。
# 全量 `-keep class org.bouncycastle.**` 会带进 5000+ 个类、DEX 涨 5 MB 量级；
# 精确 keep 这 5 个类实测只涨约 6 KB，符合"功能 > 性能 > 体积"里"体积最后"的排序，
# 但没必要为已经不需要的算法付 5 MB。
#
# ## 防回退（这才是这次真正的教训）
# 这类"字符串反射被 R8 剪断"的错误**编译期全绿、只有真机能发现**，靠人记是不行的。
# `tools/check-r8-keeps.sh` 会直接从**已签名 APK 的 classes.dex** 里断言这几条
# 关键类名确实存在，并由 `tools/publish-update.sh` 在发布前强制执行；
# 另有 `core/fs-smb` 的 `SmbCrypto` 在 SMB1 建连前做一次真实计算自检，
# 一旦再次被剪断，用户看到的是"请升级/关掉强制 SMB1"，而不是一句 `NoSuchAlgorithmException`。
-keep class org.bouncycastle.jcajce.provider.digest.MD4 { *; }
-keep class org.bouncycastle.jcajce.provider.digest.MD4$* { *; }

# sardine-android 依赖的 simple-xml 用反射读取注解来映射 XML；
# 207 Multi-Status 的解析全靠它，类与被注解字段都不能被裁剪或改名。
-keep class org.simpleframework.xml.** { *; }
-dontwarn org.simpleframework.xml.**
-keepclassmembers,allowobfuscation class * {
    @org.simpleframework.xml.* <fields>;
    @org.simpleframework.xml.* <methods>;
}

# jcifs-ng 依赖 MBassador 事件总线，它引用 Java EL（`javax.el.*`）做表达式过滤，
# Android 上同样没有这个包。我们不用表达式过滤，忽略缺类即可。
# 这一组规则由 AGP 生成在 `app/build/outputs/mapping/release/missing_rules.txt`，
# 直接取用而不是手写：`dontwarn` 的对象是**类**，漏一个就还是构建失败。
-dontwarn javax.el.BeanELResolver
-dontwarn javax.el.ELContext
-dontwarn javax.el.ELResolver
-dontwarn javax.el.ExpressionFactory
-dontwarn javax.el.FunctionMapper
-dontwarn javax.el.ValueExpression
-dontwarn javax.el.VariableMapper

# ── 压缩包（commons-compress / commons-io / tukaani-xz / junrar）──────────
#
# ## 为什么需要 dontwarn 而 **不需要** keep
# `-keep` 只在"库用反射按类名找实现"时才必要。commons-compress 全部是直接引用，
# 所以 R8 能正常分析、裁剪、改名，不需要保留任何类。
#
# ## 那为什么还要 dontwarn
# commons-compress 里有一批**我们永远不会用到**的可选编解码器，它们引用的类不在包里：
#   · `compressors/zstandard/*` → `com.github.luben:zstd-jni`（我们在 Gradle 里排除了，
#     因为它是原生库；见 `:core:archive` 的 build.gradle.kts）
#   · `compressors/brotli/*`   → `org.brotli:dec`
#   · `compressors/lz4/*`、`snappy/*` → `commons-codec`
#   · `harmony/pack200/*`      → `org.ow2.asm`（Java Pack200 实现，Android 上无意义）
# 只要不碰 `CompressorStreamFactory`（它会静态引用上述类），这些类就是不可达的、
# 会被 R8 整片删掉；但 R8 在**删除之前**仍会对它们做缺类检查并报 warning。
# 忽略这些 warning 是正确处置：既不损失功能，也不引入用不到的原生库。
-dontwarn com.github.luben.**
-dontwarn org.brotli.**
-dontwarn org.objectweb.asm.**
-dontwarn org.apache.commons.compress.harmony.**
-dontwarn org.apache.commons.compress.compressors.zstandard.**
-dontwarn org.apache.commons.compress.compressors.brotli.**
-dontwarn org.apache.commons.compress.compressors.lz4.**
-dontwarn org.apache.commons.compress.compressors.snappy.**
-dontwarn org.apache.commons.codec.**
# junrar 的可选集成（SevenZip / 日志）指向包外类
-dontwarn org.slf4j.**
-dontwarn junrar.**

# M4 无线分享：NanoHTTPD / Apache FtpServer
# -dontwarn org.apache.ftpserver.**
-dontwarn org.slf4j.**
