package com.reelix.app

import com.reelix.app.data.repo.LibraryRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTitlesKnownTest {

    @Test fun `扫描完成后名单可信`() {
        assertTrue(libraryTitlesKnown(LibraryRepository.LibraryState.Ready(emptyList()), anySourceEnabled = true))
    }

    @Test fun `启用源但尚未扫描时名单不可信`() {
        assertFalse(libraryTitlesKnown(LibraryRepository.LibraryState.Idle, anySourceEnabled = true))
        assertFalse(libraryTitlesKnown(LibraryRepository.LibraryState.Loading("扫描中"), anySourceEnabled = true))
        assertFalse(libraryTitlesKnown(LibraryRepository.LibraryState.Error("失败"), anySourceEnabled = true))
    }

    @Test fun `没有启用任何源时空名单本来就完整`() {
        assertTrue(libraryTitlesKnown(LibraryRepository.LibraryState.Idle, anySourceEnabled = false))
    }
}
