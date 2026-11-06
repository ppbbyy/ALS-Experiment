package com.example.alsexperiment

import android.os.Bundle
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.VideoView
import android.widget.MediaController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                ALSExperimentScreen()
            }
        }
    }
}

@Composable
fun ALSExperimentScreen() {

    val context = LocalContext.current

    var experimentName by remember {
        mutableStateOf("Tablet_01")
    }

    var videoName by remember {
        mutableStateOf("선택된 영상이 없습니다.")
    }

    var videoUri by remember {
        mutableStateOf<Uri?>(null)
    }

    var status by remember {
        mutableStateOf("실험 준비 완료")
    }

    // 동영상 파일 선택 기능
    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->

        if (uri != null) {

            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                // 파일 접근 권한을 유지하지 못한 경우에도
                // 현재 선택한 파일을 사용할 수 있도록 진행
            }

            videoUri = uri

            // 파일 이름 가져오기
            val cursor = context.contentResolver.query(
                uri,
                null,
                null,
                null,
                null
            )

            videoName = cursor?.use {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)

                if (index >= 0 && it.moveToFirst()) {
                    it.getString(index)
                } else {
                    "선택한 영상"
                }
            } ?: "선택한 영상"

            status = "영상 선택 완료"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        // 제목
        Text(
            text = "ALS Experiment",
            fontSize = 30.sp,
            color = MaterialTheme.colorScheme.primary
        )

        Text(
            text = "ALS 실험 데이터 수집 시스템",
            fontSize = 16.sp
        )

        HorizontalDivider()

        // 실험 설정
        Text(
            text = "실험 설정",
            fontSize = 21.sp
        )

        OutlinedTextField(
            value = experimentName,
            onValueChange = { experimentName = it },
            label = { Text("실험 번호") },
            modifier = Modifier.fillMaxWidth()
        )

        HorizontalDivider()

        // 영상 선택
        Text(
            text = "영상 설정",
            fontSize = 21.sp
        )

        Text(
            text = videoName,
            fontSize = 14.sp
        )

        Button(
            onClick = {
                videoPicker.launch(arrayOf("video/*"))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("영상 파일 선택")
        }

        // 영상 미리보기
        if (videoUri != null) {

            Text(
                text = "영상 미리보기",
                fontSize = 18.sp
            )

            val selectedUri = videoUri!!

            key(selectedUri) {
                AndroidView(
                    factory = { ctx ->

                        VideoView(ctx).apply {

                            val controller = MediaController(ctx)

                            controller.setAnchorView(this)

                            setMediaController(controller)

                            setVideoURI(selectedUri)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                )
            }
        }

        HorizontalDivider()

        // ALS 센서 상태
        Text(
            text = "ALS 센서 상태",
            fontSize = 21.sp
        )

        Text(
            text = "현재 조도: -- lux",
            fontSize = 25.sp,
            color = MaterialTheme.colorScheme.primary
        )

        Text(
            text = "실제 센서는 태블릿 연결 후 확인 가능",
            fontSize = 12.sp
        )

        HorizontalDivider()

        // 실험 제어
        Text(
            text = "실험 제어",
            fontSize = 21.sp
        )

        Button(
            onClick = {
                if (videoUri == null) {
                    status = "먼저 영상을 선택해 주세요."
                } else {
                    status = "실험 시작 기능은 다음 단계에서 구현합니다."
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("실험 시작")
        }

        OutlinedButton(
            onClick = {
                status = "실험 종료 기능은 다음 단계에서 구현합니다."
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("실험 종료")
        }

        Text(
            text = "상태: $status",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}
