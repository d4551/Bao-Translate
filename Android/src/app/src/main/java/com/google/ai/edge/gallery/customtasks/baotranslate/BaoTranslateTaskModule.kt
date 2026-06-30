package com.google.ai.edge.gallery.customtasks.baotranslate

import android.content.Context
import androidx.compose.runtime.Composable
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.litertlm.Contents
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

class BaoTranslateTask @Inject constructor() : CustomTask {
  override val task: Task =
    Task(
      id = BuiltInTaskId.BAO_TRANSLATE,
      label = "",
      labelRes = R.string.bao_translate,
      category = Category.LLM,
      iconVectorResourceId = R.drawable.ic_bao_translate,
      description = "",
      descriptionRes = R.string.bao_translate_task_description,
      shortDescription = "",
      shortDescriptionRes = R.string.bao_translate_task_short_description,
      models = mutableListOf(),
      modelCountOverride = BaoTranslateModelManager.ALL_MODELS.size,
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (String) -> Unit,
  ) {
    onDone("")
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    onDone()
  }

  @Composable
  override fun MainScreen(data: CustomTaskData) {
    BaoTranslateScreen()
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object BaoTranslateTaskModule {
  @Provides
  @IntoSet
  fun provideTask(): CustomTask {
    return BaoTranslateTask()
  }
}
