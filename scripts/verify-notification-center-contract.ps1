Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$contractPath = 'docs/notification_center.md'
$securityChecklistPath = 'docs/security_baseline_checklist.md'
$roadmapPath = 'docs/project_improvement_roadmap.md'
$ciPath = '.github/workflows/ci.yml'
$controllerPath = 'backend/src/main/java/com/playdata/calen/account/web/UserNotificationController.java'
$servicePath = 'backend/src/main/java/com/playdata/calen/account/service/UserNotificationService.java'
$repositoryPath = 'backend/src/main/java/com/playdata/calen/account/repository/UserNotificationRepository.java'
$serviceTestPath = 'backend/src/test/java/com/playdata/calen/account/service/UserNotificationServiceTest.java'
$frontendPath = 'frontend/src/components/NotificationCenterWorkspace.vue'
$stylePath = 'frontend/src/style.css'
$appPath = 'frontend/src/App.vue'
$apiPath = 'frontend/src/lib/api.js'
$ocrServicePath = 'backend/src/main/java/com/playdata/calen/ledger/ocr/LedgerOcrService.java'
$ocrServiceTestPath = 'backend/src/test/java/com/playdata/calen/ledger/ocr/LedgerOcrServiceTest.java'
$backupSchedulerPath = 'backend/src/main/java/com/playdata/calen/account/service/DataOpsBackupScheduler.java'
$backupSchedulerTestPath = 'backend/src/test/java/com/playdata/calen/account/service/DataOpsBackupSchedulerTest.java'
$budgetWarningSchedulerPath = 'backend/src/main/java/com/playdata/calen/travel/service/TravelBudgetWarningNotificationScheduler.java'
$budgetWarningSchedulerTestPath = 'backend/src/test/java/com/playdata/calen/travel/service/TravelBudgetWarningNotificationSchedulerTest.java'
$travelBudgetItemRepositoryPath = 'backend/src/main/java/com/playdata/calen/travel/repository/TravelBudgetItemRepository.java'
$travelExpenseRecordRepositoryPath = 'backend/src/main/java/com/playdata/calen/travel/repository/TravelExpenseRecordRepository.java'
$travelReminderSchedulerPath = 'backend/src/main/java/com/playdata/calen/travel/service/TravelReminderNotificationScheduler.java'
$travelReminderSchedulerTestPath = 'backend/src/test/java/com/playdata/calen/travel/service/TravelReminderNotificationSchedulerTest.java'
$travelPlanRepositoryPath = 'backend/src/main/java/com/playdata/calen/travel/repository/TravelPlanRepository.java'
$privacyServicePath = 'backend/src/main/java/com/playdata/calen/account/service/PrivacyManagementService.java'
$privacyServiceTestPath = 'backend/src/test/java/com/playdata/calen/account/PrivacyManagementServiceTest.java'

$findings = [System.Collections.Generic.List[string]]::new()

foreach ($path in @($contractPath, $securityChecklistPath, $roadmapPath, $ciPath, $controllerPath, $servicePath, $repositoryPath, $serviceTestPath, $frontendPath, $appPath, $apiPath, $ocrServicePath, $ocrServiceTestPath, $backupSchedulerPath, $backupSchedulerTestPath, $budgetWarningSchedulerPath, $budgetWarningSchedulerTestPath, $travelBudgetItemRepositoryPath, $travelExpenseRecordRepositoryPath, $travelReminderSchedulerPath, $travelReminderSchedulerTestPath, $travelPlanRepositoryPath, $privacyServicePath, $privacyServiceTestPath)) {
    if (-not (Test-Path -LiteralPath $path)) {
        $findings.Add("Missing notification center contract input: $path") | Out-Null
    }
}

if ($findings.Count -eq 0) {
    $contract = Get-Content -LiteralPath $contractPath -Raw
    $securityChecklist = Get-Content -LiteralPath $securityChecklistPath -Raw
    $roadmap = Get-Content -LiteralPath $roadmapPath -Raw
    $ci = Get-Content -LiteralPath $ciPath -Raw
    $controller = Get-Content -LiteralPath $controllerPath -Raw
    $service = Get-Content -LiteralPath $servicePath -Raw
    $repository = Get-Content -LiteralPath $repositoryPath -Raw
    $serviceTest = Get-Content -LiteralPath $serviceTestPath -Raw
    $frontend = Get-Content -LiteralPath $frontendPath -Raw
$style = Get-Content -LiteralPath $stylePath -Raw
    $app = Get-Content -LiteralPath $appPath -Raw
    $api = Get-Content -LiteralPath $apiPath -Raw
    $ocrService = Get-Content -LiteralPath $ocrServicePath -Raw
    $ocrServiceTest = Get-Content -LiteralPath $ocrServiceTestPath -Raw
    $backupScheduler = Get-Content -LiteralPath $backupSchedulerPath -Raw
    $backupSchedulerTest = Get-Content -LiteralPath $backupSchedulerTestPath -Raw
    $budgetWarningScheduler = Get-Content -LiteralPath $budgetWarningSchedulerPath -Raw
    $budgetWarningSchedulerTest = Get-Content -LiteralPath $budgetWarningSchedulerTestPath -Raw
    $travelBudgetItemRepository = Get-Content -LiteralPath $travelBudgetItemRepositoryPath -Raw
    $travelExpenseRecordRepository = Get-Content -LiteralPath $travelExpenseRecordRepositoryPath -Raw
    $travelReminderScheduler = Get-Content -LiteralPath $travelReminderSchedulerPath -Raw
    $travelReminderSchedulerTest = Get-Content -LiteralPath $travelReminderSchedulerTestPath -Raw
    $travelPlanRepository = Get-Content -LiteralPath $travelPlanRepositoryPath -Raw
    $privacyService = Get-Content -LiteralPath $privacyServicePath -Raw
    $privacyServiceTest = Get-Content -LiteralPath $privacyServiceTestPath -Raw

    foreach ($section in @('# Notification Center Contract', '## Implemented API', '## Data model', '## Event flow', '## Safety rules', '## Implemented producers', '## Event producer queue', '## Release gate', '## CI contract')) {
        if (-not $contract.Contains($section)) {
            $findings.Add("Notification center contract missing section: $section") | Out-Null
        }
    }

    foreach ($phrase in @('owner-scoped', 'redacts sensitive metadata fields', 'Budget, travel, household, and privacy producers', 'target URLs are relative application paths', 'API keys, signed URLs, raw prompts, provider responses, backup credentials, secondary PINs, public tokens, or storage paths', 'notification-center-contract')) {
        if (-not $contract.Contains($phrase)) {
            $findings.Add("Notification center contract missing required phrase: $phrase") | Out-Null
        }
    }

    foreach ($snippet in @('@RequestMapping("/api/notifications")', '@AuthenticationPrincipal AppUserPrincipal currentUser', 'getNotifications(currentUser.userId()', 'createNotification(currentUser.userId()', 'markRead(currentUser.userId()', 'markAllRead(currentUser.userId())')) {
        if (-not $controller.Contains($snippet)) {
            $findings.Add("UserNotificationController missing owner/auth snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('MAX_METADATA_LENGTH = 4000', 'SENSITIVE_METADATA_FIELD', 'SENSITIVE_QUERY_PARAM', 'BEARER_TOKEN', 'findAllByOwnerIdOrderByCreatedAtDescIdDesc(userId', 'findAllByOwnerIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(userId', 'countByOwnerIdAndReadAtIsNull(userId)', 'notification.setOwnerId(userId)', 'redactSensitiveValues(request.targetUrl())', 'redactSensitiveValues(request.metadataJson())', 'findByIdAndOwnerId(notificationId, userId)', 'markAllUnreadAsRead(userId, processedAt)')) {
        if (-not $service.Contains($snippet)) {
            $findings.Add("UserNotificationService missing owner/redaction snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('findAllByOwnerIdOrderByCreatedAtDescIdDesc', 'findAllByOwnerIdAndReadAtIsNullOrderByCreatedAtDescIdDesc', 'findByIdAndOwnerId', 'countByOwnerIdAndReadAtIsNull', 'markAllUnreadAsRead')) {
        if (-not $repository.Contains($snippet)) {
            $findings.Add("UserNotificationRepository missing owner-scoped query: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('createNotificationRedactsSensitiveMetadataAndTargetUrlBeforeSaving', 'markReadUsesOwnerScopedLookup', 'findByIdAndOwnerId(99L, USER_ID)', 'doesNotContain("sk-live-secret")', 'X-Amz-Signature=[redacted]')) {
        if (-not $serviceTest.Contains($snippet)) {
            $findings.Add("UserNotificationServiceTest missing evidence snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('fetchNotifications', 'markAllNotificationsRead', 'markNotificationRead', 'unreadOnly', 'unreadCount', 'markAllRead', 'markRead', 'openTarget', 'target.startsWith(''/''))', 'defineEmits([''unread-count-change''])', 'emit(''unread-count-change'', unreadCount.value)')) {
        if (-not $frontend.Contains($snippet)) {
            $findings.Add("NotificationCenterWorkspace missing UI snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('NotificationCenterWorkspace', 'notifications:', 'navigate(''notifications'')', 'activeRoute === ''notifications''', '@unread-count-change="handleNotificationUnreadCountChange"', 'notificationUnreadCount', 'topbar__notification-badge')) {
        if (-not $app.Contains($snippet)) {
            $findings.Add("App.vue missing notification route snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('.topbar__nav-button--notifications', '.topbar__notification-badge')) {
        if (-not $style.Contains($snippet)) {
            $findings.Add("Notification topbar badge styles missing snippet: $snippet") | Out-Null
        }
    }
    foreach ($snippet in @('fetchNotifications', 'markNotificationRead', 'markAllNotificationsRead', '/notifications/read-all')) {
        if (-not $api.Contains($snippet)) {
            $findings.Add("frontend api missing notification snippet: $snippet") | Out-Null
        }
    }


    foreach ($snippet in @('Ledger OCR failed', 'Scheduled database backup failed', 'Scheduled MinIO backup failed', 'Privacy cleanup completed', 'Travel starts tomorrow', 'Travel budget threshold exceeded', 'BACKUP_FAILED', 'AI_OR_OCR_FAILED', 'PRIVACY_ACTION_DONE', 'TRAVEL_REMINDER', 'BUDGET_WARNING')) {
        if (-not $contract.Contains($snippet)) {
            $findings.Add("Notification center contract missing implemented producer snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('private final UserNotificationService userNotificationService', 'notifyOcrFailure(userId, failureReason)', '"AI_OR_OCR_FAILED"', '"OCR analysis failed"', '"/calendar?receiptOcr=1"', '"{\"reason\":\"" + failureReason + "\"}"', 'if ("invalid_file".equals(failureReason))')) {
        if (-not $ocrService.Contains($snippet)) {
            $findings.Add("LedgerOcrService missing notification producer snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('analyzeCreatesBoundedNotificationForRemoteFailureWithoutMaskingOriginalError', 'eq("AI_OR_OCR_FAILED")', 'eq("OCR analysis failed")', 'contains("Receipt OCR could not be completed")', 'eq("/calendar?receiptOcr=1")', 'eq("{\"reason\":\"bad_request\"}")')) {
        if (-not $ocrServiceTest.Contains($snippet)) {
            $findings.Add("LedgerOcrServiceTest missing OCR notification evidence snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('private final UserNotificationService userNotificationService', 'findAllByRoleAndActiveTrueOrderByIdAsc(AppUserRole.ADMIN)', 'notifyBackupFailed("database")', 'notifyBackupFailed("minio")', '"BACKUP_FAILED"', '"Scheduled backup failed"', '"/admin?panel=data-management"', '"{\"backupType\":\"" + backupType + "\",\"status\":\"failure\"}"')) {
        if (-not $backupScheduler.Contains($snippet)) {
            $findings.Add("DataOpsBackupScheduler missing backup notification producer snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('notifiesActiveAdminsWhenScheduledDatabaseBackupFailsWithoutLeakingFailureDetails', 'findAllByRoleAndActiveTrueOrderByIdAsc(AppUserRole.ADMIN)', 'eq("BACKUP_FAILED")', 'eq("Scheduled backup failed")', 'contains("scheduled database backup failed")', 'eq("/admin?panel=data-management")', 'eq("{\"backupType\":\"database\",\"status\":\"failure\"}")')) {
        if (-not $backupSchedulerTest.Contains($snippet)) {
            $findings.Add("DataOpsBackupSchedulerTest missing backup notification evidence snippet: $snippet") | Out-Null
        }
    }


    foreach ($snippet in @('existsByOwnerIdAndTypeAndTargetUrlAndReadAtIsNull')) {
        if (-not $repository.Contains($snippet)) {
            $findings.Add("UserNotificationRepository missing budget duplicate-suppression snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('sumAmountKrwByPlanId')) {
        if (-not $travelBudgetItemRepository.Contains($snippet)) {
            $findings.Add("TravelBudgetItemRepository missing budget warning sum snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('sumAmountKrwByPlanIdAndRecordType')) {
        if (-not $travelExpenseRecordRepository.Contains($snippet)) {
            $findings.Add("TravelExpenseRecordRepository missing budget warning sum snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('findAllByStatusInOrderByStartDateDescIdDesc')) {
        if (-not $travelPlanRepository.Contains($snippet)) {
            $findings.Add("TravelPlanRepository missing budget warning query snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('class TravelBudgetWarningNotificationScheduler', 'private final TravelBudgetItemRepository travelBudgetItemRepository', 'private final TravelExpenseRecordRepository travelExpenseRecordRepository', 'private final UserNotificationRepository userNotificationRepository', 'runBudgetWarnings()', 'findAllByStatusInOrderByStartDateDescIdDesc(List.of(', 'sumAmountKrwByPlanId(planId)', 'sumAmountKrwByPlanIdAndRecordType(', 'TravelRecordType.LEDGER', 'existsByOwnerIdAndTypeAndTargetUrlAndReadAtIsNull(ownerId, NOTIFICATION_TYPE, targetUrl)', '"BUDGET_WARNING"', '"Travel budget exceeded"', '"/travel-money?planId=" + planId + "&tab=records"', 'thresholdLabel', 'over_100_percent', 'period', 'exceeded')) {
        if (-not $budgetWarningScheduler.Contains($snippet)) {
            $findings.Add("TravelBudgetWarningNotificationScheduler missing producer snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('notifiesOwnerWhenTravelSpendingExceedsBudgetWithBoundedMetadata', 'skipsDuplicateUnreadTravelBudgetWarningForSamePlan', 'eq("BUDGET_WARNING")', 'eq("Travel budget exceeded")', 'eq("/travel-money?planId=7&tab=records")', 'existsByOwnerIdAndTypeAndTargetUrlAndReadAtIsNull(', 'eq("{\"planId\":7,\"thresholdLabel\":\"over_100_percent\",\"period\":\"trip\",\"status\":\"exceeded\"}")', 'Private trip name should not be copied into budget notification metadata')) {
        if (-not $budgetWarningSchedulerTest.Contains($snippet)) {
            $findings.Add("TravelBudgetWarningNotificationSchedulerTest missing budget warning evidence snippet: $snippet") | Out-Null
        }
    }
    foreach ($snippet in @('findAllByStatusAndStartDateBetweenOrderByStartDateAscIdAsc')) {
        if (-not $travelPlanRepository.Contains($snippet)) {
            $findings.Add("TravelPlanRepository missing travel reminder query snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('class TravelReminderNotificationScheduler', 'private final TravelPlanRepository travelPlanRepository', 'private final UserNotificationService userNotificationService', 'runTravelRemindersForDate(LocalDate today)', 'findAllByStatusAndStartDateBetweenOrderByStartDateAscIdAsc(', 'TravelPlanStatus.PLANNED', '"TRAVEL_REMINDER"', '"Travel starts tomorrow"', '"/travel-money?planId=" + planId', 'starting_tomorrow')) {
        if (-not $travelReminderScheduler.Contains($snippet)) {
            $findings.Add("TravelReminderNotificationScheduler missing producer snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('notifiesOwnersWhenPlannedTravelStartsTomorrowWithBoundedMetadata', 'eq("TRAVEL_REMINDER")', 'eq("Travel starts tomorrow")', 'contains("planned trip starts tomorrow")', 'eq("/travel-money?planId=7")', 'eq("{\"planId\":7,\"dateLabel\":\"2026-07-01\",\"status\":\"starting_tomorrow\"}")', 'Private trip name should not be copied into notification metadata')) {
        if (-not $travelReminderSchedulerTest.Contains($snippet)) {
            $findings.Add("TravelReminderNotificationSchedulerTest missing travel reminder evidence snippet: $snippet") | Out-Null
        }
    }
    foreach ($snippet in @('private final UserNotificationService userNotificationService', 'notifyPrivacyCleanupComplete(userId, response)', '"PRIVACY_ACTION_DONE"', '"Privacy cleanup complete"', '"/profile?privacy=1"', '"\"aiAnalysisHistoriesDeleted\":"', '"\"publicDownloadLinksRevoked\":"', '"\"travelPublicMediaSharesRevoked\":"', '"\"photoLocationMetadataRemoved\":"')) {
        if (-not $privacyService.Contains($snippet)) {
            $findings.Add("PrivacyManagementService missing privacy notification producer snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('cleanupSensitiveDataDeletesAiHistoryAndRevokesOnlyCurrentOwnerShares', 'eq("PRIVACY_ACTION_DONE")', 'eq("Privacy cleanup complete")', 'contains("Sensitive derived data cleanup finished")', 'eq("/profile?privacy=1")', 'eq("{\"action\":\"cleanup\",\"aiAnalysisHistoriesDeleted\":3,\"publicDownloadLinksRevoked\":5,\"travelPublicMediaSharesRevoked\":6,\"photoLocationMetadataRemoved\":7}")')) {
        if (-not $privacyServiceTest.Contains($snippet)) {
            $findings.Add("PrivacyManagementServiceTest missing privacy notification evidence snippet: $snippet") | Out-Null
        }
    }
    foreach ($snippet in @('NOTIFY-01', 'Notification center', 'docs/notification_center.md', 'notification-center-contract', 'scripts/verify-notification-center-contract.ps1')) {
        if (-not $securityChecklist.Contains($snippet)) {
            $findings.Add("Security baseline missing notification center snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('Notification center', 'docs/notification_center.md', 'notification-center-contract', 'scripts/verify-notification-center-contract.ps1')) {
        if (-not $roadmap.Contains($snippet)) {
            $findings.Add("Project roadmap missing notification center snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('notification-center-contract:', './scripts/verify-notification-center-contract.ps1', '[notification-center-contract]="${{ needs[''notification-center-contract''].result }}"')) {
        if (-not $ci.Contains($snippet)) {
            $findings.Add("CI workflow missing notification center contract snippet: $snippet") | Out-Null
        }
    }
}

if ($findings.Count -gt 0) {
    Write-Host 'Notification center contract verification failed.'
    $findings | Sort-Object -Unique | ForEach-Object { Write-Host " - $_" }
    exit 1
}

Write-Host 'Notification center contract verification passed.'