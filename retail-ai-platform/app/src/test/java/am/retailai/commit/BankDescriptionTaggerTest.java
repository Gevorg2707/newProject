package am.retailai.commit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BankDescriptionTaggerTest {

    @Test
    void acquiring_recognisedInArmenianRussianEnglish() {
        assertThat(BankDescriptionTagger.tags("Վաճառքից մուտք POS Ameriabank")).contains("acquiring");
        assertThat(BankDescriptionTagger.tags("Էքվայրինգի մուտք 30.09")).contains("acquiring");
        assertThat(BankDescriptionTagger.tags("Возмещение по эквайрингу")).contains("acquiring");
    }

    @Test
    void shortTerms_matchOnlyWholeWords() {
        assertThat(BankDescriptionTagger.tags("Purpose: deposit")).doesNotContain("acquiring"); // "pos" inside "purpose"/"deposit"
        assertThat(BankDescriptionTagger.tags("Coffee shop")).doesNotContain("bank_fee");       // "fee" inside "coffee"
    }

    @Test
    void tags_neverContainPersonalData() {
        var tags = BankDescriptionTagger.tags("Վարձակալություն Պետրոսյան Արամ 1570012345670001");
        assertThat(tags).containsExactly("rent");
    }
}
