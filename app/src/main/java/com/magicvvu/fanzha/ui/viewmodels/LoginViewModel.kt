package com.magicvvu.fanzha.ui.viewmodels

import com.magicvvu.fanzha.BuildConfig
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import com.magicvvu.fanzha.data.remote.ApiClient
import com.magicvvu.fanzha.data.remote.ApiResponse
import com.magicvvu.fanzha.data.remote.LoginRequest
import com.magicvvu.fanzha.data.remote.RegisterRequest
import com.magicvvu.fanzha.data.remote.UserInfo
import org.json.JSONObject
import retrofit2.Response

class LoginViewModel : ViewModel() {
    var email by mutableStateOf("")
        private set

    var password by mutableStateOf("")
        private set

    var inviteCode by mutableStateOf("")
        private set

    var isRememberMeChecked by mutableStateOf(false)
        private set

    var isLoading by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var successMessage by mutableStateOf<String?>(null)
        private set

    var shouldSwitchToLogin by mutableStateOf(false)
        private set

    var loginSuccess by mutableStateOf(false)
        private set

    /** 登录成功时由接口返回，用于写入本地 `user_id`；消费后由 [acknowledgeLoginSuccess] 清空 */
    var userInfoAfterLogin by mutableStateOf<UserInfo?>(null)
        private set

    fun onEmailChange(newEmail: String) {
        email = newEmail
        errorMessage = null // Clear error on typing
        successMessage = null
    }

    fun onPasswordChange(newPassword: String) {
        password = newPassword
        errorMessage = null
        successMessage = null
    }

    fun onInviteCodeChange(newCode: String) {
        inviteCode = newCode
    }

    fun onRememberMeChange(checked: Boolean) {
        isRememberMeChecked = checked
    }

    var isAgreementChecked by mutableStateOf(false)
        private set

    fun onAgreementChange(checked: Boolean) {
        isAgreementChecked = checked
    }

    // Register State（1：手机号+验证码；2：密码；3：用户名/年龄/性别；4：职业）
    var registerStep by mutableStateOf(1)
        private set
    var registerEmail by mutableStateOf("")
        private set
    var registerOtp by mutableStateOf("")
        private set

    var registerPassword by mutableStateOf("")
        private set
    var registerConfirmPassword by mutableStateOf("")
        private set

    var registerUsername by mutableStateOf("")
        private set
    var registerAge by mutableStateOf("")
        private set
    var registerGender by mutableStateOf("")
        private set

    var registerOccupation by mutableStateOf("")
        private set

    fun onRegisterEmailChange(value: String) {
        registerEmail = value
        errorMessage = null
        successMessage = null
    }

    fun onRegisterOtpChange(value: String) {
        registerOtp = value
        errorMessage = null
        successMessage = null
    }

    fun onRegisterPasswordChange(value: String) {
        registerPassword = value
        errorMessage = null
        successMessage = null
    }

    fun onRegisterConfirmPasswordChange(value: String) {
        registerConfirmPassword = value
        errorMessage = null
        successMessage = null
    }

    fun onRegisterUsernameChange(value: String) {
        if (value.length <= 20) registerUsername = value
        errorMessage = null
        successMessage = null
    }

    fun onRegisterAgeChange(value: String) {
        val digits = value.filter { it.isDigit() }
        if (digits.length <= 3) registerAge = digits
        errorMessage = null
        successMessage = null
    }

    fun onRegisterGenderChange(value: String) {
        registerGender = value
        errorMessage = null
        successMessage = null
    }

    fun onRegisterOccupationChange(value: String) {
        registerOccupation = value
        errorMessage = null
        successMessage = null
    }

    fun clearError() {
        errorMessage = null
        successMessage = null
    }

    /** 切换登录/注册 Tab 时清空注册多步状态 */
    fun resetRegisterFlow() {
        registerStep = 1
        registerEmail = ""
        registerOtp = ""
        registerPassword = ""
        registerConfirmPassword = ""
        registerUsername = ""
        registerAge = ""
        registerGender = ""
        registerOccupation = ""
        errorMessage = null
        successMessage = null
    }

    fun requestRegisterOtp() {
        if (BuildConfig.REGISTRATION_OTP.isBlank()) {
            successMessage = null
            errorMessage = "短信验证码服务尚未接入，请配置本地联调验证码或使用已有账户登录"
        } else {
            successMessage = "本地联调验证码已就绪"
            errorMessage = null
        }
    }

    /** 注册第 1 步：校验协议、手机号和本地联调验证码。生产环境应改为服务端校验。 */
    fun registerStep1Next() {
        if (!isAgreementChecked) {
            errorMessage = "请先阅读并同意用户协议和隐私政策"
            return
        }
        val phone = registerEmail.trim()
        if (phone.length != 11) {
            errorMessage = "请输入完整的手机号"
            return
        }
        if (BuildConfig.REGISTRATION_OTP.isBlank()) {
            errorMessage = "短信验证码服务尚未接入"
            return
        }
        if (registerOtp.trim() != BuildConfig.REGISTRATION_OTP) {
            errorMessage = "验证码错误"
            return
        }
        errorMessage = null
        successMessage = null
        registerStep = 2
    }

    /** 注册第 2 步：校验密码后进入资料页 */
    fun registerStep2Next() {
        if (!isAgreementChecked) {
            errorMessage = "请先阅读并同意用户协议和隐私政策"
            return
        }
        if (registerStep != 2) return
        if (registerPassword.isBlank()) {
            errorMessage = "请填写密码"
            return
        }
        if (registerPassword != registerConfirmPassword) {
            errorMessage = "两次输入的密码不一致"
            return
        }
        val phone = registerEmail.trim()
        if (phone.length != 11) {
            errorMessage = "请输入完整的手机号"
            registerStep = 1
            return
        }
        errorMessage = null
        successMessage = null
        registerStep = 3
    }

    /** 注册第 3 步：校验用户名/年龄/性别后进入职业选择 */
    fun registerStep3Next() {
        if (!isAgreementChecked) {
            errorMessage = "请先阅读并同意用户协议和隐私政策"
            return
        }
        if (registerStep != 3) return
        val phone = registerEmail.trim()
        if (phone.length != 11) {
            errorMessage = "请输入完整的手机号"
            registerStep = 1
            return
        }
        if (registerPassword.isBlank() || registerPassword != registerConfirmPassword) {
            errorMessage = "请返回上一步检查密码"
            registerStep = 2
            return
        }
        val name = registerUsername.trim()
        if (name.isBlank()) {
            errorMessage = "请输入用户名"
            return
        }
        val ageInt = registerAge.trim().toIntOrNull()
        if (ageInt == null || ageInt !in 1..120) {
            errorMessage = "请输入有效年龄（1-120）"
            return
        }
        if (registerGender.isBlank()) {
            errorMessage = "请选择性别"
            return
        }
        errorMessage = null
        successMessage = null
        registerOccupation = ""
        registerStep = 4
    }

    /** 注册第 4 步：提交注册接口 */
    fun registerComplete() {
        if (!isAgreementChecked) {
            errorMessage = "请先阅读并同意用户协议和隐私政策"
            return
        }
        if (registerStep != 4) return
        val phone = registerEmail.trim()
        if (phone.length != 11) {
            errorMessage = "请输入完整的手机号"
            registerStep = 1
            return
        }
        if (registerPassword.isBlank() || registerPassword != registerConfirmPassword) {
            errorMessage = "请返回上一步检查密码"
            registerStep = 2
            return
        }
        val name = registerUsername.trim()
        if (name.isBlank()) {
            errorMessage = "请输入用户名"
            registerStep = 3
            return
        }
        val ageInt = registerAge.trim().toIntOrNull()
        if (ageInt == null || ageInt !in 1..120) {
            errorMessage = "请输入有效年龄（1-120）"
            registerStep = 3
            return
        }
        if (registerGender.isBlank()) {
            errorMessage = "请选择性别"
            registerStep = 3
            return
        }
        val job = registerOccupation.trim()
        if (job.isBlank()) {
            errorMessage = "请选择职业"
            return
        }

        viewModelScope.launch {
            isLoading = true
            errorMessage = null
            successMessage = null
            try {
                val response = ApiClient.authApi.register(
                    RegisterRequest(
                        account = phone,
                        password = registerPassword,
                        confirmPassword = registerConfirmPassword,
                        username = name,
                        age = ageInt,
                        gender = registerGender,
                        occupation = job,
                    ),
                )
                val resp = response.body()
                if (response.isSuccessful && resp?.success == true) {
                    successMessage = "注册成功"
                    email = phone
                    password = ""
                    registerStep = 1
                    registerEmail = ""
                    registerOtp = ""
                    registerPassword = ""
                    registerConfirmPassword = ""
                    registerUsername = ""
                    registerAge = ""
                    registerGender = ""
                    registerOccupation = ""
                    shouldSwitchToLogin = true
                } else {
                    errorMessage = extractErrorMessage(response, resp, "注册失败")
                }
            } catch (_: Exception) {
                errorMessage = "网络连接失败，请重试"
            } finally {
                isLoading = false
            }
        }
    }

    fun login() {
        if (!isAgreementChecked) {
            errorMessage = "请先阅读并同意用户协议和隐私政策"
            return
        }

        viewModelScope.launch {
            isLoading = true
            errorMessage = null
            successMessage = null

            try {
                val response = ApiClient.authApi.login(
                    LoginRequest(
                        account = email.trim(),
                        password = password
                    )
                )
                val resp = response.body()
                if (response.isSuccessful && resp?.success == true && resp.data != null) {
                    userInfoAfterLogin = resp.data
                    loginSuccess = true
                } else {
                    errorMessage = "密码或账号输入错误"
                }
            } catch (e: Exception) {
                errorMessage = "网络连接失败，请重试"
            } finally {
                isLoading = false
            }
        }
    }

    fun resetState() {
        loginSuccess = false
        userInfoAfterLogin = null
        errorMessage = null
        successMessage = null
        email = ""
        password = ""
        isLoading = false
    }

    fun acknowledgeLoginSuccess() {
        loginSuccess = false
        userInfoAfterLogin = null
    }

    fun consumeSwitchToLogin() {
        shouldSwitchToLogin = false
    }

    private fun extractErrorMessage(
        response: Response<ApiResponse<UserInfo>>,
        body: ApiResponse<UserInfo>?,
        fallback: String
    ): String {
        body?.message?.takeIf { it.isNotBlank() }?.let { return it }
        return try {
            val raw = response.errorBody()?.string().orEmpty()
            if (raw.isBlank()) return fallback
            val msg = JSONObject(raw).optString("message")
            if (msg.isNullOrBlank()) fallback else msg
        } catch (_: Exception) {
            fallback
        }
    }
}
