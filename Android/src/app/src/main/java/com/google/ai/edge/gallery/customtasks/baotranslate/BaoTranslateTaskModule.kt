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
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

class BaoTranslateTask @Inject constructor(
  @param:ApplicationContext private val appContext: Context,
) : CustomTask {
  override val task: Task =
    Task(
      id = BuiltInTaskId.BAO_TRANSLATE,
      label = appContext.getString(R.string.bao_translate),
      category = Category.LLM,
      iconVectorResourceId = R.drawable.ic_bao_translate,
      description = appContext.getString(R.string.bao_translate_task_description),
      shortDescription = appContext.getString(R.string.bao_translate_task_short_description),
      models = mutableListOf(),
      // Bao Translate provisions its own model set (BaoTranslateModelManager.ALL_MODELS) rather than
      // registering Model objects, so the home card derives its count from that SSOT — otherwise the
      // empty [models] list would render "0 models".
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
  fun provideTask(@ApplicationContext context: Context): CustomTask {
    return BaoTranslateTask(context)
  }
}
