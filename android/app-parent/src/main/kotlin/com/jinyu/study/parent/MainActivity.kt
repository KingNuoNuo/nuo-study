package com.jinyu.study.parent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jinyu.study.core.ui.theme.FormFactor
import com.jinyu.study.core.ui.theme.StudyTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // formFactor = Parent：字号与触控区走 Material 标准档，
            // 与孩子端共用主题代码，差异集中在 AppDimens。
            StudyTheme(formFactor = FormFactor.Parent) {
                ParentRoot()
            }
        }
    }
}
