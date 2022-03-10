/*
 * Copyright (c) 2019 DuckDuckGo
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

package com.duckduckgo.app.cta.ui

import android.content.Context
import android.net.Uri
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.duckduckgo.app.CoroutineTestRule
import com.duckduckgo.app.InstantSchedulersRule
import com.duckduckgo.app.browser.DuckDuckGoUrlDetector
import com.duckduckgo.app.browser.R
import com.duckduckgo.app.cta.db.DismissedCtaDao
import com.duckduckgo.app.cta.model.CtaId
import com.duckduckgo.app.cta.model.DismissedCta
import com.duckduckgo.app.global.db.AppDatabase
import com.duckduckgo.app.global.events.db.UserEventsStore
import com.duckduckgo.app.global.install.AppInstallStore
import com.duckduckgo.app.global.model.Site
import com.duckduckgo.app.onboarding.store.AppStage
import com.duckduckgo.app.onboarding.store.OnboardingStore
import com.duckduckgo.app.onboarding.store.UserStageStore
import com.duckduckgo.app.pixels.AppPixelName.*
import com.duckduckgo.app.privacy.db.UserWhitelistDao
import com.duckduckgo.app.privacy.model.HttpsStatus
import com.duckduckgo.app.privacy.model.PrivacyGrade
import com.duckduckgo.app.privacy.model.PrivacyPractices
import com.duckduckgo.app.privacy.model.TestEntity
import com.duckduckgo.app.settings.db.SettingsDataStore
import com.duckduckgo.app.statistics.VariantManager
import com.duckduckgo.app.statistics.VariantManager.Companion.ACTIVE_VARIANTS
import com.duckduckgo.app.statistics.pixels.Pixel
import com.duckduckgo.app.survey.db.SurveyDao
import com.duckduckgo.app.survey.model.Survey
import com.duckduckgo.app.survey.model.Survey.Status.SCHEDULED
import com.duckduckgo.app.tabs.model.TabEntity
import com.duckduckgo.app.tabs.model.TabRepository
import com.duckduckgo.app.trackerdetection.model.Entity
import com.duckduckgo.app.trackerdetection.model.TrackingEvent
import com.duckduckgo.app.widget.ui.WidgetCapabilities
import org.mockito.kotlin.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import java.util.*
import java.util.concurrent.TimeUnit

@FlowPreview
@ExperimentalCoroutinesApi
class CtaViewModelTest {

    @get:Rule
    @Suppress("unused")
    var instantTaskExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    @Suppress("unused")
    val schedulers = InstantSchedulersRule()

    @get:Rule
    @Suppress("unused")
    val coroutineRule = CoroutineTestRule()

    private lateinit var db: AppDatabase

    @Mock
    private lateinit var mockSurveyDao: SurveyDao

    @Mock
    private lateinit var mockWidgetCapabilities: WidgetCapabilities

    @Mock
    private lateinit var mockDismissedCtaDao: DismissedCtaDao

    @Mock
    private lateinit var mockPixel: Pixel

    @Mock
    private lateinit var mockAppInstallStore: AppInstallStore

    @Mock
    private lateinit var mockVariantManager: VariantManager

    @Mock
    private lateinit var mockSettingsDataStore: SettingsDataStore

    @Mock
    private lateinit var mockOnboardingStore: OnboardingStore

    @Mock
    private lateinit var mockUserWhitelistDao: UserWhitelistDao

    @Mock
    private lateinit var mockUserStageStore: UserStageStore

    @Mock
    private lateinit var mockUserEventsStore: UserEventsStore

    @Mock
    private lateinit var mockTabRepository: TabRepository

    private val requiredDaxOnboardingCtas: List<CtaId> = listOf(
        CtaId.DAX_INTRO,
        CtaId.DAX_DIALOG_SERP,
        CtaId.DAX_DIALOG_TRACKERS_FOUND,
        CtaId.DAX_DIALOG_NETWORK,
        CtaId.DAX_FIRE_BUTTON,
        CtaId.DAX_END
    )

    private lateinit var testee: CtaViewModel

    val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun before() {
        MockitoAnnotations.openMocks(this)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        whenever(mockAppInstallStore.installTimestamp).thenReturn(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1))
        whenever(mockUserWhitelistDao.contains(any())).thenReturn(false)
        whenever(mockDismissedCtaDao.dismissedCtas()).thenReturn(db.dismissedCtaDao().dismissedCtas())
        whenever(mockTabRepository.flowTabs).thenReturn(db.tabsDao().flowTabs())

        testee = CtaViewModel(
            appInstallStore = mockAppInstallStore,
            pixel = mockPixel,
            surveyDao = mockSurveyDao,
            widgetCapabilities = mockWidgetCapabilities,
            dismissedCtaDao = mockDismissedCtaDao,
            userWhitelistDao = mockUserWhitelistDao,
            settingsDataStore = mockSettingsDataStore,
            onboardingStore = mockOnboardingStore,
            userStageStore = mockUserStageStore,
            tabRepository = mockTabRepository,
            dispatchers = coroutineRule.testDispatcherProvider,
            variantManager = mockVariantManager,
            userEventsStore = mockUserEventsStore,
            duckDuckGoUrlDetector = DuckDuckGoUrlDetector()
        )

        whenever(mockVariantManager.getVariant()).thenReturn(ACTIVE_VARIANTS.first { it.key == "mi" })
    }

    @After
    fun after() {
        db.close()
    }

    @Test
    fun whenScheduledSurveyChangesAndInstalledDaysIsMinusOneThenCtaIsSurvey() = runTest {
        testee.onSurveyChanged(Survey("abc", "http://example.com", -1, SCHEDULED))
        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = false, site = null, locale = Locale.US)
        assertTrue(value is HomePanelCta.Survey)
    }

    @Test
    fun whenScheduledSurveyChangesAndInstalledDaysMatchAndLocaleIsUsThenCtaIsSurvey() = runTest {
        testee.onSurveyChanged(Survey("abc", "http://example.com", 1, SCHEDULED))
        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = false, site = null, locale = Locale.US)
        assertTrue(value is HomePanelCta.Survey)
    }

    @Test
    fun whenScheduledSurveyChangesAndInstalledDaysMatchAndLocaleIsUkThenCtaIsSurvey() = runTest {
        testee.onSurveyChanged(Survey("abc", "http://example.com", 1, SCHEDULED))
        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = false, site = null, locale = Locale.UK)
        assertTrue(value is HomePanelCta.Survey)
    }

    @Test
    fun whenScheduledSurveyChangesAndInstalledDaysMatchAndLocaleIsCanadaThenCtaIsSurvey() = runTest {
        testee.onSurveyChanged(Survey("abc", "http://example.com", 1, SCHEDULED))
        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = false, site = null, locale = Locale.CANADA)
        assertTrue(value is HomePanelCta.Survey)
    }

    @Test
    fun whenScheduledSurveyChangesAndInstalledDaysMatchButLocaleIsNotUsCanadaOrUkThenCtaIsNotSurvey() = runTest {
        testee.onSurveyChanged(Survey("abc", "http://example.com", 1, SCHEDULED))
        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = false, site = null, locale = Locale.FRANCE)
        assertFalse(value is HomePanelCta.Survey)
    }

    @Test
    fun whenScheduledSurveyIsNullThenCtaIsNotSurvey() = runTest {
        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = false, site = null, locale = Locale.US)
        assertFalse(value is HomePanelCta.Survey)
    }

    @Test
    fun whenScheduledSurveyChangesFromNullToNullThenClearedIsFalse() {
        val value = testee.onSurveyChanged(null)
        assertFalse(value)
    }

    @Test
    fun whenScheduledSurveyChangesFromNullToASurveyThenClearedIsFalse() {
        testee.onSurveyChanged(null)
        val value = testee.onSurveyChanged(Survey("abc", "http://example.com", 1, SCHEDULED))
        assertFalse(value)
    }

    @Test
    fun whenScheduledSurveyChangesFromASurveyToNullThenClearedIsTrue() {
        testee.onSurveyChanged(Survey("abc", "http://example.com", 1, SCHEDULED))
        val value = testee.onSurveyChanged(null)
        assertTrue(value)
    }

    @Test
    fun whenCtaShownAndCtaIsDaxAndCanNotSendPixelThenPixelIsNotFired() {
        testee.onCtaShown(DaxBubbleCta.DaxIntroCta(mockOnboardingStore, mockAppInstallStore))
        verify(mockPixel, never()).fire(eq(SURVEY_CTA_SHOWN), any(), any())
    }

    @Test
    fun whenCtaShownAndCtaIsDaxAndCanSendPixelThenPixelIsFired() {
        whenever(mockOnboardingStore.onboardingDialogJourney).thenReturn("s:0")
        testee.onCtaShown(DaxBubbleCta.DaxEndCta(mockOnboardingStore, mockAppInstallStore))
        verify(mockPixel, never()).fire(eq(SURVEY_CTA_SHOWN), any(), any())
    }

    @Test
    fun whenCtaShownAndCtaIsNotDaxThenPixelIsFired() {
        testee.onCtaShown(HomePanelCta.Survey(Survey("abc", "http://example.com", 1, SCHEDULED)))
        verify(mockPixel).fire(eq(SURVEY_CTA_SHOWN), any(), any())
    }

    @Test
    fun whenCtaLaunchedPixelIsFired() {
        testee.onUserClickCtaOkButton(HomePanelCta.Survey(Survey("abc", "http://example.com", 1, SCHEDULED)))
        verify(mockPixel).fire(eq(SURVEY_CTA_LAUNCHED), any(), any())
    }

    @Test
    fun whenCtaDismissedPixelIsFired() = runTest {
        testee.onUserDismissedCta(HomePanelCta.Survey(Survey("abc", "http://example.com", 1, SCHEDULED)))
        verify(mockPixel).fire(eq(SURVEY_CTA_DISMISSED), any(), any())
    }

    @Test
    fun whenSurveyCtaDismissedThenScheduledSurveysAreCancelled() = runTest {
        testee.onUserDismissedCta(HomePanelCta.Survey(Survey("abc", "http://example.com", 1, SCHEDULED)))
        verify(mockSurveyDao).cancelScheduledSurveys()
        verify(mockDismissedCtaDao, never()).insert(any())
    }

    @Test
    fun whenNonSurveyCtaDismissedCtaThenDatabaseNotified() = runTest {
        testee.onUserDismissedCta(HomePanelCta.AddWidgetAuto)
        verify(mockDismissedCtaDao).insert(DismissedCta(CtaId.ADD_WIDGET))
    }

    @Test
    fun whenCtaDismissedAndUserHasPendingOnboardingCtasThenStageNotCompleted() = runTest {
        givenOnboardingActive()
        givenShownDaxOnboardingCtas(emptyList())
        testee.onUserDismissedCta(DaxBubbleCta.DaxEndCta(mockOnboardingStore, mockAppInstallStore))
        verify(mockUserStageStore, times(0)).stageCompleted(any())
    }

    @Test
    fun whenCtaDismissedAndAllDaxOnboardingCtasShownThenStageCompleted() = runTest {
        givenOnboardingActive()
        givenShownDaxOnboardingCtas(requiredDaxOnboardingCtas)
        testee.onUserDismissedCta(DaxDialogCta.DaxSerpCta(mockOnboardingStore, mockAppInstallStore))
        verify(mockUserStageStore).stageCompleted(AppStage.DAX_ONBOARDING)
    }

    @Test
    fun whenHideTipsForeverThenPixelIsFired() = runTest {
        testee.hideTipsForever(HomePanelCta.AddWidgetAuto)
        verify(mockPixel).fire(eq(ONBOARDING_DAX_ALL_CTA_HIDDEN), any(), any())
    }

    @Test
    fun whenHideTipsForeverThenHideTipsSetToTrueOnSettings() = runTest {
        testee.hideTipsForever(HomePanelCta.AddWidgetAuto)
        verify(mockSettingsDataStore).hideTips = true
    }

    @Test
    fun whenHideTipsForeverThenDaxOnboardingStageCompleted() = runTest {
        testee.hideTipsForever(HomePanelCta.AddWidgetAuto)
        verify(mockUserStageStore).stageCompleted(AppStage.DAX_ONBOARDING)
    }

    @Test
    fun whenRegisterDaxBubbleIntroCtaThenDatabaseNotified() = runTest {
        testee.registerDaxBubbleCtaDismissed(DaxBubbleCta.DaxIntroCta(mockOnboardingStore, mockAppInstallStore))
        verify(mockDismissedCtaDao).insert(DismissedCta(CtaId.DAX_INTRO))
    }

    @Test
    fun whenRegisterDaxBubbleEndCtaThenDatabaseNotified() = runTest {
        testee.registerDaxBubbleCtaDismissed(DaxBubbleCta.DaxEndCta(mockOnboardingStore, mockAppInstallStore))
        verify(mockDismissedCtaDao).insert(DismissedCta(CtaId.DAX_END))
    }

    @Test
    fun whenRegisterCtaAndUserHasPendingOnboardingCtasThenStageNotCompleted() = runTest {
        givenOnboardingActive()
        givenShownDaxOnboardingCtas(emptyList())
        testee.registerDaxBubbleCtaDismissed(DaxBubbleCta.DaxEndCta(mockOnboardingStore, mockAppInstallStore))
        verify(mockUserStageStore, times(0)).stageCompleted(any())
    }

    @Test
    fun whenRegisterCtaAndAllDaxOnboardingCtasShownThenStageCompleted() = runTest {
        givenOnboardingActive()
        givenShownDaxOnboardingCtas(requiredDaxOnboardingCtas)
        testee.registerDaxBubbleCtaDismissed(DaxBubbleCta.DaxEndCta(mockOnboardingStore, mockAppInstallStore))
        verify(mockUserStageStore).stageCompleted(AppStage.DAX_ONBOARDING)
    }

    @Test
    fun whenRefreshCtaWhileBrowsingAndSiteIsNullThenReturnNull() = runTest {
        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = true, site = null)
        assertNull(value)
    }

    @Test
    fun whenRefreshCtaWhileBrowsingAndHideTipsIsTrueThenReturnNull() = runTest {
        whenever(mockSettingsDataStore.hideTips).thenReturn(true)
        val site = site(url = "http://www.facebook.com", entity = TestEntity("Facebook", "Facebook", 9.0))

        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = true, site = site)
        assertNull(value)
    }

    @Test
    fun whenRefreshCtaOnHomeTabAndHideTipsIsTrueAndWidgetCompatibleThenReturnWidgetCta() = runTest {
        whenever(mockSettingsDataStore.hideTips).thenReturn(true)
        whenever(mockWidgetCapabilities.supportsStandardWidgetAdd).thenReturn(true)
        whenever(mockWidgetCapabilities.supportsAutomaticWidgetAdd).thenReturn(true)

        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = false)
        assertTrue(value is HomePanelCta.AddWidgetAuto)
    }

    @Test
    fun whenRefreshCtaWhileBrowsingAndPrivacyOffForSiteThenReturnNull() = runTest {
        givenDaxOnboardingActive()
        whenever(mockUserWhitelistDao.contains(any())).thenReturn(true)
        val site = site(url = "http://www.facebook.com", entity = TestEntity("Facebook", "Facebook", 9.0))

        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = true, site = site)
        assertNull(value)
    }

    @Test
    fun whenRefreshCtaOnHomeTabAndHideTipsIsTrueThenReturnWidgetAutoCta() = runTest {
        whenever(mockSettingsDataStore.hideTips).thenReturn(true)
        whenever(mockWidgetCapabilities.supportsStandardWidgetAdd).thenReturn(true)
        whenever(mockWidgetCapabilities.supportsAutomaticWidgetAdd).thenReturn(true)
        whenever(mockWidgetCapabilities.hasInstalledWidgets).thenReturn(false)

        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = false)
        assertTrue(value is HomePanelCta.AddWidgetAuto)
    }

    @Test
    fun whenRefreshCtaOnHomeTabAndHideTipsIsTrueThenReturnWidgetInstructionsCta() = runTest {
        whenever(mockSettingsDataStore.hideTips).thenReturn(true)
        whenever(mockWidgetCapabilities.supportsStandardWidgetAdd).thenReturn(true)
        whenever(mockWidgetCapabilities.supportsAutomaticWidgetAdd).thenReturn(false)
        whenever(mockWidgetCapabilities.hasInstalledWidgets).thenReturn(false)

        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = false)
        assertTrue(value is HomePanelCta.AddWidgetInstructions)
    }

    @Test
    fun whenRefreshCtaWhileBrowsingMajorTrackerSiteThenReturnNetworkCta() = runTest {
        givenDaxOnboardingActive()
        val site = site(url = "http://www.facebook.com", entity = TestEntity("Facebook", "Facebook", 9.0))
        val expectedCtaText = context.resources.getString(
            R.string.daxMainNetworkCtaText,
            "Facebook",
            "facebook.com",
            "Facebook"
        )

        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = true, site = site) as DaxDialogCta

        assertTrue(value is DaxDialogCta.DaxMainNetworkCta)
        assertEquals(expectedCtaText, value.getDaxText(context))
    }

    @Test
    fun whenRefreshCtaWhileBrowsingOnSiteOwnedByMajorTrackerThenReturnNetworkCta() = runTest {
        givenDaxOnboardingActive()
        val site = site(url = "http://m.instagram.com", entity = TestEntity("Facebook", "Facebook", 9.0))
        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = true, site = site) as DaxDialogCta
        val expectedCtaText = context.resources.getString(
            R.string.daxMainNetworkOwnedCtaText,
            "Facebook",
            "instagram.com",
            "Facebook"
        )

        assertTrue(value is DaxDialogCta.DaxMainNetworkCta)
        assertEquals(expectedCtaText, value.getDaxText(context))
    }

    @Test
    fun whenRefreshCtaWhileBrowsingThenReturnTrackersBlockedCta() = runTest {
        givenDaxOnboardingActive()
        val trackingEvent = TrackingEvent("test.com", "test.com", null, TestEntity("test", "test", 9.0), true, null)
        val site = site(url = "http://www.cnn.com", trackerCount = 1, events = listOf(trackingEvent))
        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = true, site = site)

        assertTrue(value is DaxDialogCta.DaxTrackersBlockedCta)
    }

    @Test
    fun whenRefreshCtaWhileBrowsingAndTrackersAreNotMajorThenReturnTrackersBlockedCta() = runTest {
        givenDaxOnboardingActive()
        val trackingEvent = TrackingEvent("test.com", "test.com", null, TestEntity("test", "test", 0.123), true, null)
        val site = site(url = "http://www.cnn.com", trackerCount = 1, events = listOf(trackingEvent))
        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = true, site = site)

        assertTrue(value is DaxDialogCta.DaxTrackersBlockedCta)
    }

    @Test
    fun whenRefreshCtaWhileBrowsingAndNoTrackersInformationThenReturnNoSerpCta() = runTest {
        givenDaxOnboardingActive()
        val site = site(url = "http://www.cnn.com", trackerCount = 1)
        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = true, site = site)

        assertTrue(value is DaxDialogCta.DaxNoSerpCta)
    }

    @Test
    fun whenRefreshCtaOnSerpWhileBrowsingThenReturnSerpCta() = runTest {
        givenDaxOnboardingActive()
        val site = site(url = "http://www.duckduckgo.com")
        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = true, site = site)

        assertTrue(value is DaxDialogCta.DaxSerpCta)
    }

    @Test
    fun whenRefreshCtaWhileBrowsingThenReturnNoSerpCta() = runTest {
        givenDaxOnboardingActive()
        val site = site(url = "http://www.wikipedia.com")
        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = true, site = site)

        assertTrue(value is DaxDialogCta.DaxNoSerpCta)
    }

    @Test
    fun whenRefreshCtaOnHomeTabThenValueReturnedIsNotDaxDialogCtaType() = runTest {
        givenDaxOnboardingActive()
        val site = site(url = "http://www.wikipedia.com")
        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = false, site = site)

        assertTrue(value !is DaxDialogCta)
    }

    @Test
    fun whenRefreshCtaOnHomeTabAndIntroCtaWasNotPreviouslyShownThenIntroCtaShown() = runTest {
        givenDaxOnboardingActive()
        whenever(mockDismissedCtaDao.exists(CtaId.DAX_INTRO)).thenReturn(false)
        whenever(mockDismissedCtaDao.exists(CtaId.DAX_DIALOG_SERP)).thenReturn(true)

        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = false)
        assertTrue(value is DaxBubbleCta.DaxIntroCta)
    }

    @Test
    fun whenRefreshCtaOnHomeTabAndIntroCtaWasShownThenFireproofCtaShown() = runTest {
        whenever(mockVariantManager.getVariant()).thenReturn(ACTIVE_VARIANTS.first { it.key == "mj" })

        givenDaxOnboardingActive()
        whenever(mockDismissedCtaDao.exists(CtaId.DAX_INTRO)).thenReturn(true)
        givenAtLeastOneDaxDialogCtaShown()

        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = false)
        assertTrue(value is DaxBubbleCta.DaxFireproofCta)
    }

    @Test
    fun whenRefreshCtaOnHomeTabAndFireproofCtaWasShownThenEndCtaShown() = runTest {
        whenever(mockVariantManager.getVariant()).thenReturn(ACTIVE_VARIANTS.first { it.key == "mj" })

        givenDaxOnboardingActive()
        whenever(mockDismissedCtaDao.exists(CtaId.DAX_INTRO)).thenReturn(true)
        whenever(mockDismissedCtaDao.exists(CtaId.DAX_FIREPROOF)).thenReturn(true)
        givenAtLeastOneDaxDialogCtaShown()

        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = false)
        assertTrue(value is DaxBubbleCta.DaxEndCta)
    }

    @Test
    fun whenRefreshCtaOnHomeTabAndIntroCtaWasShownThenEndCtaShown() = runTest {
        whenever(mockVariantManager.getVariant()).thenReturn(ACTIVE_VARIANTS.first { it.key == "mi" })

        givenDaxOnboardingActive()
        whenever(mockDismissedCtaDao.exists(CtaId.DAX_INTRO)).thenReturn(true)
        givenAtLeastOneDaxDialogCtaShown()

        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = false)
        assertTrue(value is DaxBubbleCta.DaxEndCta)
    }

    @Test
    fun whenRefreshCtaWhileBrowsingWithDaxOnboardingCompletedButNotAllCtasWereShownThenReturnNull() = runTest {
        givenShownDaxOnboardingCtas(listOf(CtaId.DAX_INTRO))
        givenUserIsEstablished()

        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = true)
        assertNull(value)
    }

    @Test
    fun whenRefreshCtaInHomeTabDuringFavoriteOnboardingThenReturnNull() = runTest {
        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = false, favoritesOnboarding = true)
        assertTrue(value is BubbleCta.DaxFavoritesOnboardingCta)
    }

    @Test
    fun whenRefreshCtaWhileDaxOnboardingActiveAndBrowsingOnDuckDuckGoEmailUrlThenReturnNull() = runTest {
        givenDaxOnboardingActive()
        val site = site(url = "https://duckduckgo.com/email/")
        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = true, site = site)
        assertNull(value)
    }

    @Test
    fun whenUserHidesAllTipsThenFireButtonAnimationShouldNotShow() = runTest {
        givenOnboardingActive()
        whenever(mockSettingsDataStore.hideTips).thenReturn(true)

        assertFalse(testee.showFireButtonPulseAnimation.first())
    }

    @Test
    fun whenUserHasTwoOrMoreTabsThenFireButtonAnimationShouldNotShow() = runTest {
        givenOnboardingActive()
        db.tabsDao().insertTab(TabEntity(tabId = "0", position = 0))
        db.tabsDao().insertTab(TabEntity(tabId = "1", position = 1))

        assertFalse(testee.showFireButtonPulseAnimation.first())
    }

    @Test
    fun whenFireAnimationStopsThenDaxFireButtonDisabled() = runTest {
        givenOnboardingActive()
        db.tabsDao().insertTab(TabEntity(tabId = "0", position = 0))
        db.tabsDao().insertTab(TabEntity(tabId = "1", position = 1))

        testee.showFireButtonPulseAnimation.first()

        verify(mockDismissedCtaDao).insert(DismissedCta(CtaId.DAX_FIRE_BUTTON))
    }

    @Test
    fun whenFireButtonAnimationActiveAndUserOpensANewTabThenFireButtonAnimationStops() = runTest {
        givenOnboardingActive()
        db.tabsDao().insertTab(TabEntity(tabId = "0", position = 0))
        db.dismissedCtaDao().insert(DismissedCta(CtaId.DAX_DIALOG_TRACKERS_FOUND))
        db.tabsDao().insertTab(TabEntity(tabId = "1", position = 1))

        val collector = launch {
            testee.showFireButtonPulseAnimation.collect {
                assertFalse(it)
            }
        }
        collector.cancel()
    }

    @Test
    fun whenUserHasAlreadySeenFireButtonCtaThenFireButtonAnimationShouldNotShow() = runTest {
        givenOnboardingActive()
        whenever(mockDismissedCtaDao.exists(CtaId.DAX_FIRE_BUTTON)).thenReturn(true)

        assertFalse(testee.showFireButtonPulseAnimation.first())
    }

    @Test
    fun whenUserHasAlreadySeenFireButtonPulseAnimationThenFireButtonAnimationShouldNotShow() = runTest {
        givenOnboardingActive()
        whenever(mockDismissedCtaDao.exists(CtaId.DAX_FIRE_BUTTON_PULSE)).thenReturn(true)

        assertFalse(testee.showFireButtonPulseAnimation.first())
    }

    @Test
    fun whenTipsActiveAndUserSeesAnyTriggerFirePulseAnimationCtaThenFireButtonAnimationShouldShow() = runTest {
        givenOnboardingActive()
        val willTriggerFirePulseAnimationCtas = listOf(CtaId.DAX_DIALOG_TRACKERS_FOUND, CtaId.DAX_DIALOG_NETWORK, CtaId.DAX_DIALOG_OTHER)
        val launch = launch {
            testee.showFireButtonPulseAnimation.drop(1).collect {
                assertTrue(it)
            }
        }
        willTriggerFirePulseAnimationCtas.forEach {
            db.dismissedCtaDao().insert(DismissedCta(it))
        }

        launch.cancel()
    }

    @Test
    fun whenFirePulseAnimationDismissedThenCtaInsertedInDatabase() = runTest {
        testee.dismissPulseAnimation()

        verify(mockDismissedCtaDao).insert(DismissedCta(CtaId.DAX_FIRE_BUTTON))
        verify(mockDismissedCtaDao).insert(DismissedCta(CtaId.DAX_FIRE_BUTTON_PULSE))
    }

    @Test
    fun whenOnboardingCompletedThenNewDismissedCtasDoNotEmitValues() = runTest {
        givenDaxOnboardingCompleted()
        val launch = launch {
            testee.showFireButtonPulseAnimation.collect { /* noop */ }
        }
        clearInvocations(mockDismissedCtaDao)

        requiredDaxOnboardingCtas.forEach {
            db.dismissedCtaDao().insert(DismissedCta(it))
        }

        verifyNoMoreInteractions(mockDismissedCtaDao)
        launch.cancel()
    }

    @Test
    fun whenTipsActiveAndUserSeesAnyNonTriggerFirePulseAnimationCtaThenFireButtonAnimationShouldNotShow() = runTest {
        givenOnboardingActive()
        val willTriggerFirePulseAnimationCtas = listOf(CtaId.DAX_DIALOG_TRACKERS_FOUND, CtaId.DAX_DIALOG_NETWORK, CtaId.DAX_DIALOG_OTHER)
        val willNotTriggerFirePulseAnimationCtas = CtaId.values().toList() - willTriggerFirePulseAnimationCtas

        val launch = launch {
            testee.showFireButtonPulseAnimation.collect {
                assertFalse(it)
            }
        }
        willNotTriggerFirePulseAnimationCtas.forEach {
            db.dismissedCtaDao().insert(DismissedCta(it))
        }

        launch.cancel()
    }

    @Test
    fun whenFirstTimeUserClicksOnFireButtonThenFireDialogCtaReturned() = runTest {
        givenOnboardingActive()

        val fireDialogCta = testee.getFireDialogCta()

        assertTrue(fireDialogCta is DaxFireDialogCta.TryClearDataCta)
    }

    @Test
    fun whenFirstTimeUserClicksOnFireButtonButUserHidAllTipsThenFireDialogCtaIsNull() = runTest {
        givenOnboardingActive()
        whenever(mockSettingsDataStore.hideTips).thenReturn(true)

        val fireDialogCta = testee.getFireDialogCta()

        assertNull(fireDialogCta)
    }

    @Test
    fun whenFireCtaDismissedThenFireDialogCtaIsNull() = runTest {
        givenOnboardingActive()
        whenever(mockDismissedCtaDao.exists(CtaId.DAX_FIRE_BUTTON)).thenReturn(true)

        val fireDialogCta = testee.getFireDialogCta()

        assertNull(fireDialogCta)
    }

    private suspend fun givenDaxOnboardingActive() {
        whenever(mockUserStageStore.getUserAppStage()).thenReturn(AppStage.DAX_ONBOARDING)
    }

    private suspend fun givenDaxOnboardingCompleted() {
        whenever(mockUserStageStore.getUserAppStage()).thenReturn(AppStage.ESTABLISHED)
    }

    private suspend fun givenUserIsEstablished() {
        whenever(mockUserStageStore.getUserAppStage()).thenReturn(AppStage.ESTABLISHED)
    }

    private fun givenAtLeastOneDaxDialogCtaShown() {
        whenever(mockDismissedCtaDao.exists(CtaId.DAX_DIALOG_TRACKERS_FOUND)).thenReturn(true)
    }

    private fun givenShownDaxOnboardingCtas(shownCtas: List<CtaId>) {
        shownCtas.forEach {
            whenever(mockDismissedCtaDao.exists(it)).thenReturn(true)
        }
    }

    private suspend fun givenOnboardingActive() {
        whenever(mockUserStageStore.getUserAppStage()).thenReturn(AppStage.DAX_ONBOARDING)
    }

    private fun site(
        url: String = "http://www.test.com",
        uri: Uri? = Uri.parse(url),
        https: HttpsStatus = HttpsStatus.SECURE,
        trackerCount: Int = 0,
        events: List<TrackingEvent> = emptyList(),
        majorNetworkCount: Int = 0,
        allTrackersBlocked: Boolean = true,
        privacyPractices: PrivacyPractices.Practices = PrivacyPractices.UNKNOWN,
        entity: Entity? = null,
        grade: PrivacyGrade = PrivacyGrade.UNKNOWN,
        improvedGrade: PrivacyGrade = PrivacyGrade.UNKNOWN
    ): Site {
        val site: Site = mock()
        whenever(site.url).thenReturn(url)
        whenever(site.uri).thenReturn(uri)
        whenever(site.https).thenReturn(https)
        whenever(site.entity).thenReturn(entity)
        whenever(site.trackingEvents).thenReturn(events)
        whenever(site.trackerCount).thenReturn(trackerCount)
        whenever(site.majorNetworkCount).thenReturn(majorNetworkCount)
        whenever(site.allTrackersBlocked).thenReturn(allTrackersBlocked)
        whenever(site.privacyPractices).thenReturn(privacyPractices)
        whenever(site.calculateGrades()).thenReturn(Site.SiteGrades(grade, improvedGrade))
        return site
    }
}
