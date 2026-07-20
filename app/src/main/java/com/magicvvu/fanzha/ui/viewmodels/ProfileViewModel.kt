package com.magicvvu.fanzha.ui.viewmodels

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.magicvvu.fanzha.data.local.AuthPreferences
import com.magicvvu.fanzha.data.local.UserProfilePreferences
import com.magicvvu.fanzha.data.remote.ApiClient
import com.magicvvu.fanzha.data.remote.OccupationOptionDto
import com.magicvvu.fanzha.data.remote.UserProfileResponseDto
import com.magicvvu.fanzha.data.remote.UserProfileUpdateRequestDto
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UserProfile(
    val id: String,
    val name: String,
    val avatarUri: String? = null,
    val avatarColor: Long = 0xFF1976D2,
    val accountType: String = "普通用户",
    val age: Int = 0,
    val gender: String = "",
    /** 展示用：大类 · 具体职业（与 occupation 表一致）。 */
    val occupation: String = "",
    /** 外键 {@code users.occupation_id}，未选或未登录可能为 null。 */
    val occupationId: Int? = null,
)

internal fun formatOccupationLine(categoryName: String?, occupationName: String?): String {
    val o = occupationName?.trim().orEmpty()
    val c = categoryName?.trim().orEmpty()
    return when {
        c.isNotEmpty() && o.isNotEmpty() -> "$c · $o"
        o.isNotEmpty() -> o
        else -> ""
    }
}

class ProfileViewModel(application: Application) : AndroidViewModel(application) {

    private val defaultUser = UserProfile(
        id = AuthPreferences.getUserId(application)?.toString().orEmpty(),
        name = "用户",
        avatarColor = 0xFF1976D2,
        accountType = "普通用户",
        occupationId = null,
    )

    // Current logged-in user（与个人信息页、家庭页「本人」等共用）
    var currentUser by mutableStateOf(defaultUser)
        private set

    var availableUsers by mutableStateOf(listOf(defaultUser))
        private set

    /** 职业下拉数据（与库表 occupation + occupation_category 一致）。 */
    var occupationOptions by mutableStateOf<List<OccupationOptionDto>>(emptyList())
        private set

    /** 个人信息页展示：拉取/保存资料结果（失败时便于排查）。 */
    var profileRemoteHint by mutableStateOf<String?>(null)
        private set

    var isSwitching by mutableStateOf(false)
        private set
        
    var switchError by mutableStateOf<String?>(null)
        private set

    init {
        UserProfilePreferences.load(getApplication())?.let { stored ->
            currentUser = stored
        }
    }

    /**
     * 已登录时拉取 `users` 与职业关联展示字段，并加载职业列表（用于修改职业）。
     * [remoteUserId] 须与 [AuthPreferences] 中一致；由界面传入以便登录后重组时能再次触发拉取。
     */
    fun refreshProfileAndOccupations(remoteUserId: Long) {
        if (remoteUserId <= 0L) return
        viewModelScope.launch {
            try {
                val occ = withContext(Dispatchers.IO) {
                    ApiClient.userProfileApi.listOccupations()
                }
                if (occ.isSuccessful && occ.body()?.success == true) {
                    occupationOptions = occ.body()?.data.orEmpty()
                    profileRemoteHint = null
                } else {
                    profileRemoteHint = "职业列表加载失败（${occ.code()}）"
                }
                val prof = withContext(Dispatchers.IO) {
                    ApiClient.userProfileApi.getProfile(remoteUserId)
                }
                val body = prof.body()
                if (prof.isSuccessful && body?.success == true && body.data != null) {
                    currentUser = enrichOccupationFromOptions(mergeServerProfile(body.data!!, currentUser))
                    persistCurrentUser()
                    profileRemoteHint = null
                } else {
                    val msg = body?.message?.takeIf { it.isNotBlank() } ?: "HTTP ${prof.code()}"
                    profileRemoteHint = "资料同步失败：$msg（请确认后端已部署 /user/profile）"
                }
            } catch (e: Exception) {
                profileRemoteHint = "资料同步异常：${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun clearProfileRemoteHint() {
        profileRemoteHint = null
    }

    /**
     * 以服务端为准合并展示字段（库中为空则显示空/0，不再回退到本地演示数据），仅保留本机头像路径。
     */
    /**
     * 服务端 JOIN 未带出名称时，用已加载的 [occupationOptions]（occupation 表）补全展示文案。
     */
    private fun enrichOccupationFromOptions(user: UserProfile): UserProfile {
        if (user.occupation.isNotBlank()) return user
        val id = user.occupationId ?: return user
        val opt = occupationOptions.find { it.id == id } ?: return user
        val line = formatOccupationLine(opt.categoryName, opt.occupationName)
        return if (line.isNotEmpty()) user.copy(occupation = line) else user
    }

    private fun mergeServerProfile(dto: UserProfileResponseDto, previous: UserProfile): UserProfile {
        val occLine = formatOccupationLine(dto.categoryName, dto.occupationName)
        val displayName = dto.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: dto.account?.trim()?.takeIf { it.isNotEmpty() }
            ?: dto.phone?.trim().orEmpty()
        return UserProfile(
            id = dto.userId.toString(),
            name = displayName,
            avatarUri = previous.avatarUri,
            avatarColor = previous.avatarColor,
            accountType = previous.accountType,
            age = dto.age ?: 0,
            gender = dto.gender?.trim().orEmpty(),
            occupation = occLine,
            occupationId = dto.occupationId,
        )
    }

    private fun persistCurrentUser() {
        UserProfilePreferences.save(getApplication(), currentUser)
    }

    private fun syncProfileToServer(snapshot: UserProfile) {
        val uid = AuthPreferences.getUserId(getApplication()) ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ApiClient.userProfileApi.updateProfile(
                        uid,
                        UserProfileUpdateRequestDto(
                            name = snapshot.name,
                            age = snapshot.age,
                            gender = snapshot.gender,
                            occupationId = snapshot.occupationId,
                        ),
                    )
                }
            }
            result.onFailure { e ->
                profileRemoteHint = "保存到服务器失败：${e.message ?: e.javaClass.simpleName}"
            }.onSuccess { resp ->
                val body = resp.body()
                if (!resp.isSuccessful || body == null || !body.success) {
                    profileRemoteHint = body?.message?.takeIf { it.isNotBlank() }
                        ?: "保存失败（HTTP ${resp.code()}）"
                } else {
                    profileRemoteHint = null
                }
            }
        }
    }

    /**
     * 将相册等返回的 [content] URI 复制到应用私有目录，避免进程重启后临时授权失效导致头像无法加载。
     * 若已是可读的本机绝对路径则直接沿用。
     */
    private fun resolvePersistableAvatarPath(uriString: String): String {
        val context = getApplication<Application>()
        if (uriString.startsWith("/")) {
            val f = File(uriString)
            if (f.isFile && f.exists()) return f.absolutePath
        }
        val parsed = try {
            Uri.parse(uriString)
        } catch (_: Exception) {
            return uriString
        }
        if (parsed.scheme == "file") {
            val path = parsed.path ?: return uriString
            val f = File(path)
            if (f.isFile && f.exists()) return f.absolutePath
        }
        return try {
            val dir = File(context.filesDir, "profile_avatar").apply { mkdirs() }
            val dest = File(dir, "user_avatar.jpg")
            context.contentResolver.openInputStream(parsed)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return uriString
            dest.absolutePath
        } catch (_: Exception) {
            uriString
        }
    }

    fun switchUser(targetUser: UserProfile) {
        if (targetUser.id == currentUser.id) return
        currentUser = targetUser
        persistCurrentUser()
    }

    fun updateAvatar(uri: String) {
        val stablePath = resolvePersistableAvatarPath(uri)
        currentUser = currentUser.copy(avatarUri = stablePath)
        persistCurrentUser()
    }

    fun updateName(name: String) {
        val next = currentUser.copy(name = name)
        currentUser = next
        persistCurrentUser()
        syncProfileToServer(next)
    }

    fun updateAge(age: Int) {
        val next = currentUser.copy(age = age)
        currentUser = next
        persistCurrentUser()
        syncProfileToServer(next)
    }

    fun updateGender(gender: String) {
        val next = currentUser.copy(gender = gender)
        currentUser = next
        persistCurrentUser()
        syncProfileToServer(next)
    }

    /** [occupationId] 为 {@code occupation.id}；展示文案由选项表拼出。 */
    fun updateOccupationById(occupationId: Int) {
        val opt = occupationOptions.find { it.id == occupationId }
        val line = opt?.let { formatOccupationLine(it.categoryName, it.occupationName) }.orEmpty()
        val next = currentUser.copy(
            occupationId = occupationId,
            occupation = line.ifEmpty { currentUser.occupation },
        )
        currentUser = next
        persistCurrentUser()
        syncProfileToServer(next)
    }
    
    fun clearError() {
        switchError = null
    }
}
