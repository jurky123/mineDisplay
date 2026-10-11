package com.minedisplay;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** MediaStore.checkId 单测：ID 规则（纯逻辑，无 Bukkit 依赖）。 */
class MediaIdTest {

    @Test
    void acceptsSimpleIds() {
        assertNull(MediaStore.checkId("lobby"));
        assertNull(MediaStore.checkId("poster-01"));
        assertNull(MediaStore.checkId("pic_2"));
        assertNull(MediaStore.checkId("a"));
    }

    @Test
    void rejectsPathsAndWeirdIds() {
        assertNotNull(MediaStore.checkId(null));
        assertNotNull(MediaStore.checkId(""));
        assertNotNull(MediaStore.checkId("a/b"));
        assertNotNull(MediaStore.checkId("a\\b"));
        assertNotNull(MediaStore.checkId("a:b"));
        assertNotNull(MediaStore.checkId("ABC"));
        assertNotNull(MediaStore.checkId("中文"));
        assertNotNull(MediaStore.checkId("a".repeat(33)));
    }
}
