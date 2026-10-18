package com.miyuetech.mimifiles.feature.connection

import com.miyuetech.mimifiles.core.fssmb.SmbTestResult
import com.miyuetech.mimifiles.core.fssmb.SmbTester
import com.miyuetech.mimifiles.core.fswebdav.WebDavTestResult
import com.miyuetech.mimifiles.core.fswebdav.WebDavTester
import com.miyuetech.mimifiles.core.model.ConnectionProfile

/**
 * 测试结果的统一形态。
 *
 * 两个后端模块各自定义了自己的结果类型（它们**不应该**互相知道对方存在），
 * 收敛在这里——本模块是唯一"同时认识 SMB 与 WebDAV"的地方，这个翻译职责
 * 天然属于它。
 */
internal sealed interface TestOutcome {
    /** @param detail 要显示在成功提示里的补充信息（方言、认证方式） */
    data class Success(val detail: String) : TestOutcome

    data class Failure(val reason: String, val retryable: Boolean) : TestOutcome
}

/**
 * 用**还没保存**的表单内容打一次真实连接。
 *
 * 这是连接页最有价值的一个动作：用户改完密码/共享名之后，能在离开表单之前
 * 知道"到底能不能连"，而不是保存、返回、点进目录、失败、再回来。
 */
internal object ConnectionTester {

    suspend fun test(profile: ConnectionProfile, password: String?): TestOutcome =
        when (profile.scheme) {
            "smb" -> SmbTester.test(profile, password).toOutcome()

            "webdav" -> WebDavTester.test(profile, password).toOutcome()

            else -> TestOutcome.Failure("未知的连接类型：${profile.scheme}", retryable = false)
        }

    private fun SmbTestResult.toOutcome(): TestOutcome = when (this) {
        is SmbTestResult.Success -> TestOutcome.Success(dialect)
        is SmbTestResult.Failure -> TestOutcome.Failure(reason, retryable)
    }

    private fun WebDavTestResult.toOutcome(): TestOutcome = when (this) {
        is WebDavTestResult.Success -> TestOutcome.Success("认证：$authLabel")
        is WebDavTestResult.Failure -> TestOutcome.Failure(reason, retryable)
    }
}
