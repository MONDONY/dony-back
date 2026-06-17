package com.dony.api.matching;

import com.dony.api.common.DonyBusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BidContentRulesTest {

    private AnnouncementEntity announcementRefusing(List<String> refused) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setRefusedTypes(refused);
        return a;
    }

    @Test
    void throwsWhenDeclaredCategoryIsRefused_caseInsensitive() {
        AnnouncementEntity a = announcementRefusing(List.of("Hi-fi", "Téléphone"));
        assertThatThrownBy(() -> BidContentRules.assertNotRefused(a, "Vêtements, hi-fi"))
                .isInstanceOf(DonyBusinessException.class)
                .satisfies(e -> assertThat(((DonyBusinessException) e).getErrorCode())
                        .isEqualTo("content-type-refused"));
    }

    @Test
    void passesWhenNoOverlap() {
        AnnouncementEntity a = announcementRefusing(List.of("Hi-fi"));
        assertThatCode(() -> BidContentRules.assertNotRefused(a, "Vêtements, Médicaments"))
                .doesNotThrowAnyException();
    }

    @Test
    void passesWhenCategoryBlankOrNull() {
        AnnouncementEntity a = announcementRefusing(List.of("Hi-fi"));
        assertThatCode(() -> BidContentRules.assertNotRefused(a, "")).doesNotThrowAnyException();
        assertThatCode(() -> BidContentRules.assertNotRefused(a, "   ")).doesNotThrowAnyException();
        assertThatCode(() -> BidContentRules.assertNotRefused(a, null)).doesNotThrowAnyException();
    }

    @Test
    void passesWhenRefusedListEmptyOrNull() {
        assertThatCode(() -> BidContentRules.assertNotRefused(announcementRefusing(List.of()), "Vêtements"))
                .doesNotThrowAnyException();
        assertThatCode(() -> BidContentRules.assertNotRefused(announcementRefusing(null), "Vêtements"))
                .doesNotThrowAnyException();
    }

    @Test
    void ignoresEmptyTokensFromTrailingCommas() {
        AnnouncementEntity a = announcementRefusing(List.of("Hi-fi"));
        // Tokens vides (",,") ne doivent pas matcher un refusé vide hypothétique.
        assertThatCode(() -> BidContentRules.assertNotRefused(a, "Vêtements,, ,"))
                .doesNotThrowAnyException();
    }
}
