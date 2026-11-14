package studio.acmenra.control.di

import org.koin.core.context.startKoin
import org.koin.dsl.KoinAppDeclaration

fun initKoin(appDeclaration: KoinAppDeclaration = {}) {
    val koinApp =
        startKoin {
            appDeclaration()
            modules(appModule)
        }
    koinApp.koin.createEagerInstances()
}
