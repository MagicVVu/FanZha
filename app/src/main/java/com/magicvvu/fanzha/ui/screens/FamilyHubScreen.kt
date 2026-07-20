package com.magicvvu.fanzha.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.magicvvu.fanzha.ui.components.AppButton
import com.magicvvu.fanzha.ui.components.AppOutlinedButton
import com.magicvvu.fanzha.ui.components.AppTextField
import com.magicvvu.fanzha.ui.components.AppTopBar
import kotlinx.coroutines.launch

/**
 * 进入「家庭」Tab 时的入口：创建家庭或加入家庭（输入家庭码）。
 */
@Composable
fun FamilyHubScreen(
    onCreateFamily: () -> Unit,
    onJoinFamilySuccess: () -> Unit,
) {
    var showJoinDialog by remember { mutableStateOf(false) }
    var familyCode by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(title = "家庭") },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppButton(
                text = "创建家庭",
                onClick = onCreateFamily,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(16.dp))
            AppOutlinedButton(
                text = "加入家庭",
                onClick = { showJoinDialog = true },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (showJoinDialog) {
        AlertDialog(
            onDismissRequest = {
                showJoinDialog = false
                familyCode = ""
            },
            title = {
                Text(
                    text = "加入家庭",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                AppTextField(
                    value = familyCode,
                    onValueChange = { familyCode = it },
                    label = "请输入家庭码",
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (familyCode.isBlank()) {
                            scope.launch {
                                snackbarHostState.showSnackbar("请输入家庭码")
                            }
                        } else {
                            showJoinDialog = false
                            familyCode = ""
                            onJoinFamilySuccess()
                        }
                    },
                ) {
                    Text("确认")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showJoinDialog = false
                        familyCode = ""
                    },
                ) {
                    Text("取消")
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
}
