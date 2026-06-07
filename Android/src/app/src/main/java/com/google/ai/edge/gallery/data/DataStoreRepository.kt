/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.data

import androidx.datastore.core.DataStore
import com.google.ai.edge.gallery.proto.AccessTokenData
import com.google.ai.edge.gallery.proto.BenchmarkResult
import com.google.ai.edge.gallery.proto.BenchmarkResults
import com.google.ai.edge.gallery.proto.Cutout
import com.google.ai.edge.gallery.proto.CutoutCollection
import com.google.ai.edge.gallery.proto.ImportedModel
import com.google.ai.edge.gallery.proto.Settings
import com.google.ai.edge.gallery.proto.Skill
import com.google.ai.edge.gallery.proto.Skills
import com.google.ai.edge.gallery.proto.Theme
import com.google.ai.edge.gallery.proto.UserData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

// TODO(b/423700720): Async migration — DONE for all writes (now `suspend`, no runBlocking; callers
// moved onto coroutine scopes). Reads are deliberately split, not blanket-converted:
//   - STARTUP-GATE reads stay synchronous ON PURPOSE — they must return a value before the first
//     frame, and going async would flash/flicker the wrong UI (a regression):
//       * readTheme  -> Application.onCreate (device-verified: setting Light then cold-starting shows
//         Light from frame 1, no dark flash);
//       * isTosAccepted / isGemmaTermsOfUseAccepted -> the TOS gate;
//       * hasViewedPromo -> GalleryNavGraph first-screen selection (home-direct vs promo).
//   - The remaining VM-internal list reads (readImportedModels, getAllSkills, getAllBenchmarkResults,
//     readTextInputHistory, readAccessTokenData) can move to `suspend`/Flow incrementally; each is a
//     per-call-chain change (read return values cascade `suspend` upward) with marginal hot-path gain.
interface DataStoreRepository {
  suspend fun saveTextInputHistory(history: List<String>)
  fun readTextInputHistory(): List<String>
  suspend fun saveTheme(theme: Theme)
  fun readTheme(): Theme
  suspend fun saveSecret(key: String, value: String)
  fun readSecret(key: String): String?
  suspend fun deleteSecret(key: String)
  suspend fun saveAccessTokenData(accessToken: String, refreshToken: String, expiresAt: Long)
  suspend fun clearAccessTokenData()
  fun readAccessTokenData(): AccessTokenData?
  suspend fun saveImportedModels(importedModels: List<ImportedModel>)
  fun readImportedModels(): List<ImportedModel>
  fun isTosAccepted(): Boolean
  suspend fun acceptTos()
  fun isGemmaTermsOfUseAccepted(): Boolean
  suspend fun acceptGemmaTermsOfUse()
  fun getHasRunTinyGarden(): Boolean
  suspend fun setHasRunTinyGarden(hasRun: Boolean)
  suspend fun addCutout(cutout: Cutout)
  fun getAllCutouts(): List<Cutout>
  suspend fun setCutout(newCutout: Cutout)
  suspend fun setCutouts(cutouts: List<Cutout>)
  suspend fun setHasSeenBenchmarkComparisonHelp(seen: Boolean)
  fun getHasSeenBenchmarkComparisonHelp(): Boolean
  suspend fun addBenchmarkResult(result: BenchmarkResult)
  fun getAllBenchmarkResults(): List<BenchmarkResult>
  suspend fun deleteBenchmarkResult(index: Int)
  suspend fun addSkill(skill: Skill)
  suspend fun setSkills(skills: List<Skill>)
  suspend fun setSkillSelected(skill: Skill, selected: Boolean)
  suspend fun setAllSkillsSelected(selected: Boolean)
  fun getAllSkills(): List<Skill>
  suspend fun deleteSkill(name: String)
  suspend fun deleteSkills(names: Set<String>)
  suspend fun addViewedPromoId(promoId: String)
  suspend fun removeViewedPromoId(promoId: String)
  fun hasViewedPromo(promoId: String): Boolean
  suspend fun setHasDismissedBaoTranslateWelcome(dismissed: Boolean)
  fun getHasDismissedBaoTranslateWelcome(): Boolean
  suspend fun setBaoTranslateSettings(settings: BaoTranslateStoredSettings)
  /** Returns persisted Bao Translate settings, or null if they have never been saved. */
  fun getBaoTranslateSettings(): BaoTranslateStoredSettings?
}

/** Persisted, variable Bao Translate preferences. */
data class BaoTranslateStoredSettings(
  val translationModel: String,
  val sourceLanguage: String,
  val targetLanguage: String,
  val wifiOnlyDownloads: Boolean,
)

/** Repository for managing data using Proto DataStore. */
class DefaultDataStoreRepository(
  private val dataStore: DataStore<Settings>,
  private val userDataDataStore: DataStore<UserData>,
  private val cutoutDataStore: DataStore<CutoutCollection>,
  private val benchmarkResultsDataStore: DataStore<BenchmarkResults>,
  private val skillsDataStore: DataStore<Skills>,
) : DataStoreRepository {
  override suspend fun saveTextInputHistory(history: List<String>) {
    dataStore.updateData { settings ->
      settings.toBuilder().clearTextInputHistory().addAllTextInputHistory(history).build()
    }
  }

  override fun readTextInputHistory(): List<String> {
    return runBlocking { dataStore.data.first().textInputHistoryList }
  }

  override suspend fun saveTheme(theme: Theme) {
    dataStore.updateData { settings -> settings.toBuilder().setTheme(theme).build() }
  }

  override fun readTheme(): Theme {
    return runBlocking {
      val curTheme = dataStore.data.first().theme
      if (curTheme == Theme.THEME_UNSPECIFIED) Theme.THEME_AUTO else curTheme
    }
  }

  override suspend fun saveSecret(key: String, value: String) {
    userDataDataStore.updateData { it.toBuilder().putSecrets(key, value).build() }
  }

  override fun readSecret(key: String): String? {
    return runBlocking { userDataDataStore.data.first().secretsMap[key] }
  }

  override suspend fun deleteSecret(key: String) {
    userDataDataStore.updateData { it.toBuilder().removeSecrets(key).build() }
  }

  override suspend fun saveAccessTokenData(accessToken: String, refreshToken: String, expiresAt: Long) {
    dataStore.updateData { settings ->
      settings.toBuilder().setAccessTokenData(AccessTokenData.getDefaultInstance()).build()
    }
    userDataDataStore.updateData { userData ->
      userData.toBuilder().setAccessTokenData(
        AccessTokenData.newBuilder()
          .setAccessToken(accessToken)
          .setRefreshToken(refreshToken)
          .setExpiresAtMs(expiresAt)
          .build()
      ).build()
    }
  }

  override suspend fun clearAccessTokenData() {
    dataStore.updateData { it.toBuilder().clearAccessTokenData().build() }
    userDataDataStore.updateData { it.toBuilder().clearAccessTokenData().build() }
  }

  override fun readAccessTokenData(): AccessTokenData? {
    return runBlocking { userDataDataStore.data.first().accessTokenData }
  }

  override suspend fun saveImportedModels(importedModels: List<ImportedModel>) {
    dataStore.updateData { it.toBuilder().clearImportedModel().addAllImportedModel(importedModels).build() }
  }

  override fun readImportedModels(): List<ImportedModel> {
    return runBlocking { dataStore.data.first().importedModelList }
  }

  override fun isTosAccepted(): Boolean {
    return runBlocking { dataStore.data.first().isTosAccepted }
  }

  override suspend fun acceptTos() {
    dataStore.updateData { it.toBuilder().setIsTosAccepted(true).build() }
  }

  override fun isGemmaTermsOfUseAccepted(): Boolean {
    return runBlocking { dataStore.data.first().isGemmaTermsAccepted }
  }

  override suspend fun acceptGemmaTermsOfUse() {
    dataStore.updateData { it.toBuilder().setIsGemmaTermsAccepted(true).build() }
  }

  override fun getHasRunTinyGarden(): Boolean {
    return runBlocking { dataStore.data.first().hasRunTinyGarden }
  }

  override suspend fun setHasRunTinyGarden(hasRun: Boolean) {
    dataStore.updateData { it.toBuilder().setHasRunTinyGarden(hasRun).build() }
  }

  override suspend fun addCutout(cutout: Cutout) {
    cutoutDataStore.updateData { it.toBuilder().addCutout(cutout).build() }
  }

  override fun getAllCutouts(): List<Cutout> {
    return runBlocking { cutoutDataStore.data.first().cutoutList }
  }

  override suspend fun setCutout(newCutout: Cutout) {
    cutoutDataStore.updateData { cutouts ->
      var index = -1
      for (i in 0..<cutouts.cutoutCount) {
        if (cutouts.cutoutList[i].id == newCutout.id) { index = i; break }
      }
      if (index >= 0) cutouts.toBuilder().setCutout(index, newCutout).build() else cutouts
    }
  }

  override suspend fun setCutouts(cutouts: List<Cutout>) {
    cutoutDataStore.updateData { CutoutCollection.newBuilder().addAllCutout(cutouts).build() }
  }

  override suspend fun setHasSeenBenchmarkComparisonHelp(seen: Boolean) {
    dataStore.updateData { it.toBuilder().setHasSeenBenchmarkComparisonHelp(seen).build() }
  }

  override fun getHasSeenBenchmarkComparisonHelp(): Boolean {
    return runBlocking { dataStore.data.first().hasSeenBenchmarkComparisonHelp }
  }

  override suspend fun addBenchmarkResult(result: BenchmarkResult) {
    benchmarkResultsDataStore.updateData { it.toBuilder().addResult(0, result).build() }
  }

  override fun getAllBenchmarkResults(): List<BenchmarkResult> {
    return runBlocking { benchmarkResultsDataStore.data.first().resultList }
  }

  override suspend fun deleteBenchmarkResult(index: Int) {
    benchmarkResultsDataStore.updateData { it.toBuilder().removeResult(index).build() }
  }

  override suspend fun addSkill(skill: Skill) {
    skillsDataStore.updateData { skills ->
      val newSkills = buildList { add(skill); addAll(skills.skillList) }
      skills.toBuilder().clearSkill().addAllSkill(newSkills).build()
    }
  }

  override suspend fun setSkills(skills: List<Skill>) {
    skillsDataStore.updateData { it.toBuilder().clearSkill().addAllSkill(skills).build() }
  }

  override suspend fun setSkillSelected(skill: Skill, selected: Boolean) {
    skillsDataStore.updateData { skills ->
      val newSkills = skills.skillList.map { cur ->
        if (cur.name == skill.name) cur.toBuilder().setSelected(selected).setUserModifiedSelection(true).build() else cur
      }
      Skills.newBuilder().addAllSkill(newSkills).build()
    }
  }

  override suspend fun setAllSkillsSelected(selected: Boolean) {
    skillsDataStore.updateData { skills ->
      val newSkills = skills.skillList.map { it.toBuilder().setSelected(selected).setUserModifiedSelection(true).build() }
      Skills.newBuilder().addAllSkill(newSkills).build()
    }
  }

  override fun getAllSkills(): List<Skill> {
    return runBlocking { skillsDataStore.data.first().skillList }
  }

  override suspend fun deleteSkill(name: String) {
    skillsDataStore.updateData { skills ->
      val newSkills = skills.skillList.filter { it.name != name }
      Skills.newBuilder().addAllSkill(newSkills).build()
    }
  }

  override suspend fun deleteSkills(names: Set<String>) {
    skillsDataStore.updateData { skills ->
      val newSkills = skills.skillList.filter { it.name !in names }
      skills.toBuilder().clearSkill().addAllSkill(newSkills).build()
    }
  }

  override suspend fun addViewedPromoId(promoId: String) {
    dataStore.updateData { settings ->
      if (settings.viewedPromoIdList.contains(promoId)) settings
      else settings.toBuilder().addViewedPromoId(promoId).build()
    }
  }

  override suspend fun removeViewedPromoId(promoId: String) {
    dataStore.updateData { settings ->
      val newList = settings.viewedPromoIdList.filter { it != promoId }
      settings.toBuilder().clearViewedPromoId().addAllViewedPromoId(newList).build()
    }
  }

  override fun hasViewedPromo(promoId: String): Boolean {
    return runBlocking { dataStore.data.first().viewedPromoIdList.contains(promoId) }
  }

  override suspend fun setHasDismissedBaoTranslateWelcome(dismissed: Boolean) {
    dataStore.updateData { it.toBuilder().setHasDismissedBaoTranslateWelcome(dismissed).build() }
  }

  override fun getHasDismissedBaoTranslateWelcome(): Boolean {
    return runBlocking { dataStore.data.first().hasDismissedBaoTranslateWelcome }
  }

  override suspend fun setBaoTranslateSettings(settings: BaoTranslateStoredSettings) {
    dataStore.updateData {
      it.toBuilder()
        .setBaoTranslateTranslationModel(settings.translationModel)
        .setBaoTranslateSourceLanguage(settings.sourceLanguage)
        .setBaoTranslateTargetLanguage(settings.targetLanguage)
        .setBaoTranslateWifiOnlyDownloads(settings.wifiOnlyDownloads)
        .setBaoTranslateSettingsInitialized(true)
        .build()
    }
  }

  override fun getBaoTranslateSettings(): BaoTranslateStoredSettings? {
    return runBlocking {
      val s = dataStore.data.first()
      if (!s.baoTranslateSettingsInitialized) {
        null
      } else {
        BaoTranslateStoredSettings(
          translationModel = s.baoTranslateTranslationModel,
          sourceLanguage = s.baoTranslateSourceLanguage,
          targetLanguage = s.baoTranslateTargetLanguage,
          wifiOnlyDownloads = s.baoTranslateWifiOnlyDownloads,
        )
      }
    }
  }
}
