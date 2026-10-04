package com.verseangelscript.rider.lang;

import com.intellij.codeInsight.CodeInsightSettings;
import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actionSystem.*;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.psi.*;
import com.intellij.testFramework.EdtTestUtil;
import com.jetbrains.rider.test.OpenSolutionParams;
import com.jetbrains.rider.test.annotations.*;
import com.jetbrains.rider.test.enums.*;
import com.jetbrains.rider.test.enums.sdk.SdkVersion;
import com.jetbrains.rider.test.junit5.base.PerTestSolutionTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

@Solution(name = "vas-navigation", slnName = "VasNavigation.sln")
@TestSettings(buildTool = BuildTool.AUTODETECT, mono = Mono.NONE, sdkVersion = SdkVersion.NONE)
public final class VasRiderEditingIntegrationTest extends PerTestSolutionTestBase {
    @Override public void modifyOpenSolutionParams(OpenSolutionParams params) {
        super.modifyOpenSolutionParams(params); params.setWaitForCaches(true); params.setWaitForSolutionBuilder(true); params.setRestoreNuGetPackages(false);
    }
    @Test @Tag("season/vas") void nativeTypingPairsSkipsDeletesAndIndents() {
        withEditor(editor -> {
            Project project = editor.getProject();
            var settings = CodeInsightSettings.getInstance(); boolean brackets = settings.AUTOINSERT_PAIR_BRACKET, quotes = settings.AUTOINSERT_PAIR_QUOTE;
            settings.AUTOINSERT_PAIR_BRACKET = true; settings.AUTOINSERT_PAIR_QUOTE = true;
            try {
                for (char c : new char[] {'{', '(', '[', '"', '\''}) {
                    setText(editor, "", 0); type(editor, c);
                    assertEquals("" + c + VasTypedHandler.closing(c), editor.getDocument().getText(), "native pair " + c);
                    assertEquals(1, editor.getCaretModel().getOffset());
                    action(editor, IdeActions.ACTION_EDITOR_BACKSPACE);
                    assertEquals("", editor.getDocument().getText(), "paired backspace " + c);
                    type(editor, c); type(editor, VasTypedHandler.closing(c));
                    assertEquals(2, editor.getDocument().getTextLength(), "closing must not duplicate " + c);
                    assertEquals(2, editor.getCaretModel().getOffset());
                }
                setText(editor, "void Main() ", 12); type(editor, '{'); action(editor, IdeActions.ACTION_EDITOR_ENTER);
                assertEquals("void Main() {\n\t\n}", editor.getDocument().getText());
                assertEquals("void Main() {\n\t".length(), editor.getCaretModel().getOffset());
                setText(editor, "// comment ", 11); type(editor, '{'); assertEquals("// comment {", editor.getDocument().getText());
                setText(editor, "\"inside\"", 7); type(editor, '('); assertEquals("\"inside(\"", editor.getDocument().getText());
                setText(editor, "int Value = 1", 13); type(editor, '\''); assertEquals("int Value = 1'", editor.getDocument().getText());
                setText(editor, "int Value = 0xAF", 16); type(editor, '\''); assertEquals("int Value = 0xAF'", editor.getDocument().getText());
                setText(editor, "Value", 5); editor.getSelectionModel().setSelection(0, 5); type(editor, '(');
                assertEquals("(Value)", editor.getDocument().getText()); editor.getSelectionModel().removeSelection();
                settings.AUTOINSERT_PAIR_BRACKET = false; setText(editor, "", 0); type(editor, '{'); assertEquals("{", editor.getDocument().getText());
            } finally { settings.AUTOINSERT_PAIR_BRACKET = brackets; settings.AUTOINSERT_PAIR_QUOTE = quotes; }
        });
    }
    @Test @Tag("season/vas") void nativeCompletionInsertsCallAndProvidesOverloadSignatures() {
        withEditor(editor -> {
            String source = "int Add(int Left, int Right) { return Left + Right; }\nvoid Main() { Ad }";
            setText(editor, source, source.lastIndexOf("Ad") + 2);
            new CodeCompletionHandlerBase(CompletionType.BASIC).invokeCompletion(editor.getProject(), editor);
            var lookup = LookupManager.getActiveLookup(editor);
            if (lookup != null) {
                var item = lookup.getItems().stream().filter(i -> i.getLookupString().equals("Add")).findFirst().orElseThrow();
                lookup.setCurrentItem(item); ((com.intellij.codeInsight.lookup.impl.LookupImpl) lookup).finishLookup('\t');
            }
            assertTrue(editor.getDocument().getText().contains("Main() { Add() }"), editor.getDocument().getText());
            assertEquals(')', editor.getDocument().getCharsSequence().charAt(editor.getCaretModel().getOffset()));
            PsiDocumentManager.getInstance(editor.getProject()).commitAllDocuments();
            PsiFile file = PsiDocumentManager.getInstance(editor.getProject()).getPsiFile(editor.getDocument());
            var call = VasCallSupport.callAt(file.getText(), editor.getCaretModel().getOffset());
            assertEquals("Add", call.name());
            assertEquals("Add(int Left, int Right)", VasCallSupport.signatures(file, call).getFirst().label());
        });
    }
    private void withEditor(Consumer<Editor> body) {
        Project project = getSolutionApiFacade().getProject();
        var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(getSolutionApiFacade().getActiveSolutionDirectory().resolve("src/main.vas"));
        assertNotNull(file);
        EdtTestUtil.runInEdtAndWait(() -> {
            Editor editor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, file), true);
            assertNotNull(editor); String before = editor.getDocument().getText();
            try { body.accept(editor); } finally { LookupManager.hideActiveLookup(project); setText(editor, before, 0); FileEditorManager.getInstance(project).closeFile(file); }
        });
    }
    private static void setText(Editor editor, String text, int caret) {
        LookupManager.hideActiveLookup(editor.getProject());
        WriteCommandAction.runWriteCommandAction(editor.getProject(), () -> editor.getDocument().setText(text));
        editor.getCaretModel().moveToOffset(caret);
        PsiDocumentManager.getInstance(editor.getProject()).commitAllDocuments();
    }
    private static DataContext context(Editor editor) {
        return SimpleDataContext.builder().add(CommonDataKeys.PROJECT, editor.getProject()).add(CommonDataKeys.EDITOR, editor)
            .add(CommonDataKeys.PSI_FILE, PsiDocumentManager.getInstance(editor.getProject()).getPsiFile(editor.getDocument())).build();
    }
    private static void type(Editor editor, char c) { TypedAction.getInstance().actionPerformed(editor, c, context(editor)); }
    private static void action(Editor editor, String name) {
        WriteCommandAction.runWriteCommandAction(editor.getProject(), () -> EditorActionManager.getInstance().getActionHandler(name)
            .execute(editor, editor.getCaretModel().getCurrentCaret(), context(editor)));
    }
}
