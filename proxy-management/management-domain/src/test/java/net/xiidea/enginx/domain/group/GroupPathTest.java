package net.xiidea.enginx.domain.group;

import net.xiidea.enginx.domain.shared.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GroupPathTest {

    @Test
    void buildsANestedPath() {
        GroupPath path = GroupPath.root("production").child("eu").child("web");
        assertThat(path.value()).isEqualTo("production.eu.web");
        assertThat(path.depth()).isEqualTo(3);
    }

    @Test
    void listsAncestorsFromTheRootDown() {
        assertThat(new GroupPath("production.eu.web").ancestorPaths())
                .containsExactly("production", "production.eu");
    }

    @Test
    void aRootHasNoAncestors() {
        assertThat(new GroupPath("production").ancestorPaths()).isEmpty();
    }

    @Test
    @DisplayName("descendant matching does not confuse a sibling that shares a prefix")
    void descendantMatchingIsPrefixSafe() {
        GroupPath production = new GroupPath("production");
        assertThat(new GroupPath("production.eu").isDescendantOf(production)).isTrue();
        assertThat(new GroupPath("production").isDescendantOf(production)).isFalse();
        // 'production-legacy' shares the first ten characters but is a different tree
        assertThat(new GroupPath("production-legacy").isDescendantOf(production)).isFalse();
        assertThat(production.descendantPrefix()).isEqualTo("production.");
    }

    @Test
    void rejectsSegmentsThatWouldBreakThePathEncoding() {
        assertThatThrownBy(() -> GroupPath.root("has space")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> GroupPath.root("-leading")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new GroupPath("a..b")).isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsExcessiveNesting() {
        GroupPath path = GroupPath.root("a");
        for (int i = 1; i < GroupPath.MAX_DEPTH; i++) {
            path = path.child("s" + i);
        }
        GroupPath deepest = path;
        assertThatThrownBy(() -> deepest.child("toofar")).isInstanceOf(ValidationException.class);
    }
}
