package com.playdata.calen.travel.domain.policy;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class TravelClusterPagePolicyTest {
    record Photo(Long id, LocalDate expenseDate, LocalTime expenseTime, LocalDateTime uploadedAt, String contentUrl) {}
    @Test void matchesThePreviousSharedAndOwnedInMemoryPagingWithStableTiesAndMissingDates() {
        Random random = new Random(3107);
        for (int run=0; run<300; run++) {
            List<Photo> photos = new ArrayList<>();
            for (int i=0; i<random.nextInt(100); i++) photos.add(new Photo(random.nextBoolean() ? (long)random.nextInt(40) : null,
                    random.nextBoolean() ? LocalDate.of(2026,10,1+random.nextInt(4)) : null,
                    random.nextBoolean() ? LocalTime.of(random.nextInt(24),0) : null,
                    random.nextBoolean() ? LocalDateTime.of(2026,10,1,random.nextInt(24),0) : null,
                    "/api/travel/public/map-shares/token/media/"+i+"/content"));
            Long representative = (long)random.nextInt(40), focus = random.nextBoolean() ? (long)random.nextInt(40) : null;
            Integer page = random.nextBoolean() ? random.nextInt(6)-1 : null, size = random.nextBoolean() ? random.nextInt(60)-2 : null;
            var actual = TravelClusterPagePolicy.slice(photos, representative, focus, page, size,
                    Photo::id, Photo::expenseDate, Photo::expenseTime, Photo::uploadedAt);
            var expected = previous(photos, representative, focus, page, size);
            assertThat(actual).isEqualTo(expected);
            for (var photo : actual.photos()) assertThat(photos).anyMatch(original -> original == photo);
        }
    }
    @Test void firstPagePrioritizesFocusThenRepresentativeButOtherPagesIgnoreFocus() {
        var photos = List.of(photo(1L), photo(2L), photo(3L));
        assertThat(slice(photos, 2L, 1L, 0, 3).photos()).extracting(Photo::id).containsExactly(1L,2L,3L);
        assertThat(slice(photos, 2L, 1L, 1, 1).photos()).extracting(Photo::id).containsExactly(3L);
    }
    @Test void normalizesBoundsAndNullListsWithoutChangingEmptyPageContract() {
        assertThat(slice(null,null,null,null,null)).isEqualTo(new TravelClusterPagePolicy.Slice<Photo>(List.of(),0,12,0,false));
        assertThat(slice(List.of(photo(1L)),null,null,-1,0).size()).isEqualTo(1);
        assertThat(slice(List.of(photo(1L)),null,null,10,999).photos()).isEmpty();
        assertThat(TravelClusterPagePolicy.size(999)).isEqualTo(36);
    }
    @Test void retainsThePreviousExtremeInMemoryPageErrorInsteadOfQuietlyChangingInvalidInputHandling() {
        var photos=List.of(photo(1L));
        assertThatThrownBy(() -> previous(photos,null,null,Integer.MAX_VALUE,36)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> slice(photos,null,null,Integer.MAX_VALUE,36)).isInstanceOf(IndexOutOfBoundsException.class);
    }
    static Photo photo(Long id) { return new Photo(id,null,null,null,"/public/media/"+id); }
    static TravelClusterPagePolicy.Slice<Photo> slice(List<Photo> photos,Long rep,Long focus,Integer p,Integer s) {
        return TravelClusterPagePolicy.slice(photos,rep,focus,p,s,Photo::id,Photo::expenseDate,Photo::expenseTime,Photo::uploadedAt);
    }
    // Frozen pre-refactor algorithm: custom public/share URLs remain on the exact original objects.
    static TravelClusterPagePolicy.Slice<Photo> previous(List<Photo> input,Long representative,Long focus,Integer page,Integer size) {
        int p=Math.max(page==null?0:page,0), s=Math.min(Math.max(size==null?12:size,1),36);
        Long first=p==0?focus:null;
        List<Photo> photos=input==null?List.of():input;
        var order=Comparator.comparingInt((Photo photo)->first!=null&&photo!=null&&photo.id()!=null&&photo.id().equals(first)?0:1)
                .thenComparingInt(photo->representative!=null&&photo!=null&&photo.id()!=null&&photo.id().equals(representative)?0:1)
                .thenComparing(Photo::expenseDate,Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Photo::expenseTime,Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Photo::uploadedAt,Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Photo::id,Comparator.nullsLast(Comparator.reverseOrder()));
        var sorted=photos.stream().sorted(order).toList();
        int total=sorted.size(), from=Math.min(p*s,total), to=Math.min(from+s,total);
        return new TravelClusterPagePolicy.Slice<>(sorted.subList(from,to),p,s,total,to<total);
    }
}
