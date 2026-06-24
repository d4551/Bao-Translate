package com.google.ai.edge.gallery.customtasks.libredrop

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

class LibreDropTask @Inject constructor(
  @param:ApplicationContext private val appContext: Context,
) : CustomTask {
  override val task: Task =
    Task(
      id = BuiltInTaskId.LIBRE_DROP,
      label = appContext.getString(R.string.libre_drop),
      category = Category.LLM,
      iconVectorResourceId = R.drawable.ic_libre_drop,
      description = appContext.getString(R.string.libre_drop_task_description),
      shortDescription = appContext.getString(R.string.libre_drop_task_short_description),
      models = mutableListOf(),
      modelCountOverride = 2,
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
    LibreDropScreen(data = data)
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object LibreDropTaskModule {
  @Provides
  @IntoSet
  fun provideTask(@ApplicationContext context: Context): CustomTask {
    return LibreDropTask(context)
  }
}
