package app.eikon.gallery.core.di

import app.eikon.gallery.data.db.IndexStage
import app.eikon.gallery.data.indexing.AnalysisEnvironment
import app.eikon.gallery.data.indexing.GeoStageProcessor
import app.eikon.gallery.data.indexing.IndexingRepository
import app.eikon.gallery.data.indexing.OcrStageProcessor
import app.eikon.gallery.data.indexing.RunnerEnvironment
import app.eikon.gallery.data.indexing.StageProcessor
import app.eikon.gallery.data.indexing.WorkQueue
import app.eikon.gallery.data.ocr.OcrEngine
import app.eikon.gallery.data.ocr.TesseractOcrEngine
import dagger.Binds
import dagger.MapKey
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap

@MapKey
annotation class IndexStageKey(val value: IndexStage)

/** Wires the analysis pipeline. A new stage is one more `@IntoMap` line and its processor. */
@Module
@InstallIn(SingletonComponent::class)
abstract class IndexingModule {
    @Binds
    abstract fun workQueue(impl: IndexingRepository): WorkQueue

    @Binds
    abstract fun runnerEnvironment(impl: AnalysisEnvironment): RunnerEnvironment

    @Binds
    abstract fun ocrEngine(impl: TesseractOcrEngine): OcrEngine

    @Binds
    @IntoMap
    @IndexStageKey(IndexStage.GEO)
    abstract fun geoProcessor(impl: GeoStageProcessor): StageProcessor

    @Binds
    @IntoMap
    @IndexStageKey(IndexStage.OCR)
    abstract fun ocrProcessor(impl: OcrStageProcessor): StageProcessor
}
