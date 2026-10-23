package com.jinyu.study.parent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.jinyu.study.feature.capture.CaptureScreen
import com.jinyu.study.feature.mirror.MirrorScreen
import com.jinyu.study.feature.report.ReportScreen
import com.jinyu.study.feature.settings.ServerSettingsScreen

/**
 * 家长端顶层导航。「看孩子」这个 Tab 是后来加的，理由见下。
 *
 * 原本刻意只有三件事：拍照录入、看报告、改设置 —— 家长端每多一个 Tab，
 * 家长每天点开它的概率就低一分。加「画面」（实时看孩子平板的界面）是因为它
 * 和「录入」是同一件事的两头：家长拍完作业想知道孩子那边到底收到没有、
 * 现在是不是在做；让孩子念给你听不现实，让他截图也不会。
 *
 * 除此之外仍然不加：「作业列表 / 题库 / 错题本」都不该出现在首屏 ——
 * 那些是孩子端的事，家长端的价值在于少而准。
 */
@Composable
fun ParentRoot(modifier: Modifier = Modifier) {
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.PhotoCamera, contentDescription = null) },
                    label = { Text("录入") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Visibility, contentDescription = null) },
                    label = { Text("画面") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.Insights, contentDescription = null) },
                    label = { Text("学情") },
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("设置") },
                )
            }
        },
    ) { inner ->
        Box(modifier = Modifier.padding(inner)) {
            when (tab) {
                0 -> CaptureScreen()
                1 -> MirrorScreen()
                2 -> ReportScreen()
                // 设置页与孩子端共用同一份实现（:feature-settings）。
                // ⚠️ stackFields = true：手机竖屏可用宽度约 360dp，
                //    两个输入框并排后每个只剩 ~170dp —— 填 `192.168.1.10:8092`
                //    这种 13 字符的地址会被截断，家长没法核对自己填对了没。
                //   没有 onClose：设置就是底部第四个 Tab，返回靠系统返回键即可。
                else -> ServerSettingsScreen(stackFields = true)
            }
        }
    }
}
