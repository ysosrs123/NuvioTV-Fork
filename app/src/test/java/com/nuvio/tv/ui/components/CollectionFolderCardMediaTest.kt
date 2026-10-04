package com.nuvio.tv.ui.components

import com.nuvio.tv.domain.model.PosterShape
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionFolderCardMediaTest {
    @Test fun wideTileCropsCoversThatAreNotWide() {
        assertTrue(wideFolderCoverNeedsCrop(PosterShape.LANDSCAPE, 500, 750))
        assertTrue(wideFolderCoverNeedsCrop(PosterShape.LANDSCAPE, 600, 600))
        assertTrue(wideFolderCoverNeedsCrop(PosterShape.LANDSCAPE, 1000, 185))
    }

    @Test fun wideTileKeepsStretchingCoversMadeForIt() {
        assertFalse(wideFolderCoverNeedsCrop(PosterShape.LANDSCAPE, 1280, 720))
        assertFalse(wideFolderCoverNeedsCrop(PosterShape.LANDSCAPE, 800, 500))
        assertFalse(wideFolderCoverNeedsCrop(PosterShape.LANDSCAPE, 0, 0))
    }

    @Test fun otherTileShapesAreLeftAlone() {
        assertFalse(wideFolderCoverNeedsCrop(PosterShape.POSTER, 1280, 720))
        assertFalse(wideFolderCoverNeedsCrop(PosterShape.SQUARE, 500, 750))
    }
}
