package com.playdata.calen.common.config;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.AppUserRole;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.service.AccountSetupService;
import com.playdata.calen.ledger.domain.CategoryDetail;
import com.playdata.calen.ledger.domain.CategoryGroup;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.LedgerEntry;
import com.playdata.calen.ledger.domain.PaymentMethod;
import com.playdata.calen.ledger.repository.CategoryDetailRepository;
import com.playdata.calen.ledger.repository.CategoryGroupRepository;
import com.playdata.calen.ledger.repository.LedgerEntryRepository;
import com.playdata.calen.ledger.repository.PaymentMethodRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true")
public class DataInitializer {

    private final AppUserRepository appUserRepository;
    private final AccountSetupService accountSetupService;
    private final CategoryGroupRepository categoryGroupRepository;
    private final CategoryDetailRepository categoryDetailRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final PasswordEncoder passwordEncoder;

    @Bean
    ApplicationRunner initializeSampleData() {
        return args -> {
            if (appUserRepository.count() > 0 || ledgerEntryRepository.count() > 0) {
                return;
            }

            AppUser admin = createUser("admin", "관리자", "test1234", "12345678", AppUserRole.ADMIN);
            AppUser hana = createUser("hana", "김하나", "test1234", "12345678", AppUserRole.USER);
            AppUser minsu = createUser("minsu", "박민수", "test1234", "87654321", AppUserRole.USER);

            accountSetupService.initializeDefaults(admin);
            accountSetupService.initializeDefaults(hana);
            accountSetupService.initializeDefaults(minsu);

            seedEntriesForHana(hana);
            seedEntriesForMinsu(minsu);
        };
    }

    private AppUser createUser(String loginId, String displayName, String password, String secondaryPin, AppUserRole role) {
        AppUser user = new AppUser();
        user.setLoginId(loginId);
        user.setDisplayName(displayName);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setSecondaryPinHash(passwordEncoder.encode(secondaryPin));
        user.setRole(role);
        user.setActive(true);
        return appUserRepository.save(user);
    }

    private void seedEntriesForHana(AppUser owner) {
        LocalDate today = LocalDate.now();
        ledgerEntryRepository.saveAll(List.of(
                entry(owner, today.minusDays(1), "점심 약속", "팀 식사", new BigDecimal("15000"), EntryType.EXPENSE, "식비", "외식", "신한카드"),
                entry(owner, today.minusDays(1), "커피", "오후 간식", new BigDecimal("6500"), EntryType.EXPENSE, "식비", "군것질", "포인트"),
                entry(owner, today.minusDays(2), "마트 장보기", "주간 식재료", new BigDecimal("48200"), EntryType.EXPENSE, "식비", "장보기", "우리카드"),
                entry(owner, today.minusDays(3), "지하철", "출퇴근", new BigDecimal("1500"), EntryType.EXPENSE, "교통", "대중교통", "현금"),
                entry(owner, today.minusDays(5), "택시", "야근 후 귀가", new BigDecimal("18900"), EntryType.EXPENSE, "교통", "택시", "신한카드"),
                entry(owner, today.minusDays(6), "병원", "정기 검진", new BigDecimal("35000"), EntryType.EXPENSE, "생활", "의료", "우리카드"),
                entry(owner, today.withDayOfMonth(1), "월급", "정기 급여", new BigDecimal("3200000"), EntryType.INCOME, "급여", "본급", "계좌이체"),
                entry(owner, today.minusDays(8), "프리랜서 정산", "주간 디자인 작업", new BigDecimal("450000"), EntryType.INCOME, "부수입", "프리랜서", "계좌이체"),
                entry(owner, today.minusDays(10), "쇼핑", "생활용품", new BigDecimal("79000"), EntryType.EXPENSE, "생활", "쇼핑", "우리카드"),
                entry(owner, today.minusMonths(1).withDayOfMonth(3), "지난달 월급", "정기 급여", new BigDecimal("3200000"), EntryType.INCOME, "급여", "본급", "계좌이체"),
                entry(owner, today.minusMonths(1).withDayOfMonth(8), "성과급", "분기 보너스", new BigDecimal("800000"), EntryType.INCOME, "급여", "보너스", "계좌이체"),
                entry(owner, today.minusMonths(1).withDayOfMonth(12), "외식", "주말 가족 모임", new BigDecimal("87000"), EntryType.EXPENSE, "식비", "외식", "신한카드")
        ));
    }

    private void seedEntriesForMinsu(AppUser owner) {
        LocalDate today = LocalDate.now();
        ledgerEntryRepository.saveAll(List.of(
                entry(owner, today.minusDays(1), "월급", "개발팀 급여", new BigDecimal("4100000"), EntryType.INCOME, "급여", "본급", "계좌이체"),
                entry(owner, today.minusDays(2), "장보기", "주말 마트", new BigDecimal("63500"), EntryType.EXPENSE, "식비", "장보기", "우리카드"),
                entry(owner, today.minusDays(4), "저녁 외식", "친구 모임", new BigDecimal("54000"), EntryType.EXPENSE, "식비", "외식", "신한카드"),
                entry(owner, today.minusDays(5), "버스", "출장 이동", new BigDecimal("2800"), EntryType.EXPENSE, "교통", "대중교통", "현금"),
                entry(owner, today.minusDays(7), "병원비 환급", "보험 처리", new BigDecimal("130000"), EntryType.INCOME, "부수입", "환급", "계좌이체"),
                entry(owner, today.minusMonths(1).withDayOfMonth(11), "사이드 프로젝트", "앱 유지보수", new BigDecimal("720000"), EntryType.INCOME, "부수입", "프리랜서", "계좌이체"),
                entry(owner, today.minusMonths(1).withDayOfMonth(15), "옷 쇼핑", "봄 옷 구매", new BigDecimal("189000"), EntryType.EXPENSE, "생활", "쇼핑", "우리카드")
        ));
    }

    private LedgerEntry entry(
            AppUser owner,
            LocalDate entryDate,
            String title,
            String memo,
            BigDecimal amount,
            EntryType entryType,
            String groupName,
            String detailName,
            String paymentMethodName
    ) {
        CategoryGroup categoryGroup = categoryGroupRepository.findAllByOwnerIdAndActiveTrueOrderByDisplayOrderAscIdAsc(owner.getId()).stream()
                .filter(group -> group.getName().equals(groupName))
                .findFirst()
                .orElseThrow();

        CategoryDetail categoryDetail = categoryDetailRepository.findAllByGroupIdOrderByDisplayOrderAscIdAsc(categoryGroup.getId()).stream()
                .filter(detail -> detail.getName().equals(detailName))
                .findFirst()
                .orElseThrow();

        PaymentMethod paymentMethod = paymentMethodRepository.findAllByOwnerIdAndActiveTrueOrderByDisplayOrderAscIdAsc(owner.getId()).stream()
                .filter(method -> method.getName().equals(paymentMethodName))
                .findFirst()
                .orElseThrow();

        LedgerEntry ledgerEntry = new LedgerEntry();
        ledgerEntry.setOwner(owner);
        ledgerEntry.setEntryDate(entryDate);
        ledgerEntry.setEntryTime(java.time.LocalTime.of((Math.abs(title.hashCode()) % 10) + 8, 0));
        ledgerEntry.setTitle(title);
        ledgerEntry.setMemo(memo);
        ledgerEntry.setAmount(amount);
        ledgerEntry.setEntryType(entryType);
        ledgerEntry.setCategoryGroup(categoryGroup);
        ledgerEntry.setCategoryDetail(categoryDetail);
        ledgerEntry.setPaymentMethod(paymentMethod);
        return ledgerEntry;
    }
}
