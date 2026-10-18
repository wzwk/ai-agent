package dev.example.productai.rag;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class RagIndexTransactionServiceTest {

    @Test
    void fileReplacementAndDeletionDeclareTransactionBoundaries() throws Exception {
        Method replace = RagIndexTransactionService.class.getMethod(
                "replaceFile", String.class, String.class, String.class, String.class,
                long.class, int.class, java.util.List.class);
        Method delete = RagIndexTransactionService.class.getMethod("markDeleted", String.class, String.class);

        assertNotNull(replace.getAnnotation(Transactional.class));
        assertNotNull(delete.getAnnotation(Transactional.class));
    }
}
