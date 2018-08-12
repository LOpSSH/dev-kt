/**
 * Copied from the tests in Analyzer compiler
 * @author ice1000
 */
package org.ice1000.devkt

import org.ice1000.devkt.openapi.util.selfLocationFile
import org.jetbrains.kotlin.analyzer.AnalysisResult
import org.jetbrains.kotlin.backend.jvm.JvmIrCodegenFactory
import org.jetbrains.kotlin.cli.common.output.outputUtils.writeAllTo
import org.jetbrains.kotlin.cli.common.repl.KotlinJsr223JvmScriptEngineFactoryBase
import org.jetbrains.kotlin.cli.common.repl.ScriptArgsWithTypes
import org.jetbrains.kotlin.cli.jvm.compiler.*
import org.jetbrains.kotlin.codegen.*
import org.jetbrains.kotlin.codegen.state.GenerationState
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.load.kotlin.*
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.AnalyzingUtils
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.script.jsr223.KotlinJsr223JvmLocalScriptEngine
import org.jetbrains.kotlin.script.jsr223.KotlinStandardJsr223ScriptTemplate
import java.io.File
import javax.script.*

fun analyzeAndCheckForErrors(file: KtFile, environment: KotlinCoreEnvironment): AnalysisResult =
		analyzeAndCheckForErrors(setOf(file), environment)

fun analyzeAndCheckForErrors(files: Collection<KtFile>, environment: KotlinCoreEnvironment): AnalysisResult =
		analyzeAndCheckForErrors(environment.project, files, environment.configuration, environment::createPackagePartProvider)

fun analyzeAndCheckForErrors(
		project: Project,
		files: Collection<KtFile>,
		configuration: CompilerConfiguration,
		packagePartProvider: (GlobalSearchScope) -> PackagePartProvider,
		trace: BindingTrace = CliBindingTrace()
): AnalysisResult {
	for (file in files) AnalyzingUtils.checkForSyntacticErrors(file)

	return analyze(project, files, configuration, packagePartProvider, trace).apply {
		AnalyzingUtils.throwExceptionOnErrors(bindingContext)
	}
}

fun analyze(file: KtFile, environment: KotlinCoreEnvironment): AnalysisResult =
		analyze(setOf(file), environment)

fun analyze(files: Collection<KtFile>, environment: KotlinCoreEnvironment): AnalysisResult =
		analyze(files, environment, environment.configuration)

fun analyze(
		files: Collection<KtFile>,
		environment: KotlinCoreEnvironment,
		configuration: CompilerConfiguration): AnalysisResult =
		analyze(environment.project, files, configuration, environment::createPackagePartProvider)

private fun analyze(
		project: Project,
		files: Collection<KtFile>,
		configuration: CompilerConfiguration,
		packagePartProviderFactory: (GlobalSearchScope) -> PackagePartProvider,
		trace: BindingTrace = CliBindingTrace()
): AnalysisResult = TopDownAnalyzerFacadeForJVM.analyzeFilesWithJavaIntegration(
		project, files, trace, configuration, packagePartProviderFactory
)

fun compileFileTo(ktFile: KtFile, environment: KotlinCoreEnvironment, output: File): ClassFileFactory =
		compileFile(ktFile, environment).apply { writeAllTo(output) }

fun compileFile(ktFile: KtFile, environment: KotlinCoreEnvironment): ClassFileFactory =
		compileFiles(listOf(ktFile), environment).factory

fun compileFiles(
		files: List<KtFile>,
		environment: KotlinCoreEnvironment,
		classBuilderFactory: ClassBuilderFactory = ClassBuilderFactories.TEST,
		trace: BindingTrace = CliBindingTrace()
): GenerationState =
		compileFiles(files, environment.configuration, classBuilderFactory, environment::createPackagePartProvider, trace)

fun compileFiles(
		files: List<KtFile>,
		configuration: CompilerConfiguration,
		classBuilderFactory: ClassBuilderFactory,
		packagePartProvider: (GlobalSearchScope) -> PackagePartProvider,
		trace: BindingTrace = CliBindingTrace()
): GenerationState {
	val analysisResult =
			analyzeAndCheckForErrors(files.first().project, files, configuration, packagePartProvider, trace)
	analysisResult.throwIfError()

	val state = GenerationState.Builder(
			files.first().project, classBuilderFactory, analysisResult.moduleDescriptor, analysisResult.bindingContext,
			files, configuration
	).codegenFactory(
			if (configuration.getBoolean(JVMConfigurationKeys.IR)) JvmIrCodegenFactory else DefaultCodegenFactory
	).build()
	if (analysisResult.shouldGenerateCode)
		KotlinCodegenFacade.compileCorrectFiles(state, CompilationErrorHandler.THROW_EXCEPTION)

	// For JVM-specific errors
	AnalyzingUtils.throwExceptionOnErrors(state.collectedExtraJvmDiagnostics)
	return state
}

object DevKtScriptEngineFactory : KotlinJsr223JvmScriptEngineFactoryBase() {
	override fun getScriptEngine(): ScriptEngine = KotlinJsr223JvmLocalScriptEngine(
			this,
			listOf(selfLocationFile),
			KotlinStandardJsr223ScriptTemplate::class.java.canonicalName,
			{ ctx, types ->
				ScriptArgsWithTypes(arrayOf(ctx.getBindings(ScriptContext.ENGINE_SCOPE)), types ?: emptyArray())
			},
			arrayOf(Bindings::class)
	)
}

fun compileScript(file: KtFile, configuration: CompilerConfiguration) {

}
