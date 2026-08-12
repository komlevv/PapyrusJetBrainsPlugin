package dev.papyrus.jetbrains.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PapyrusEnterIndentHandlerBehaviorTest {
    @Test
    void matchesExactUpstreamIncreaseRules() {
        for (String line : new String[]{
                "If Ready",
                "While Ready",
                "String Property Label",
                "Struct Entry",
                "Group Runtime",
                "State Waiting",
                "Event OnInit()",
                "Function Test()",
                "Int Function Value()",
                "Else",
                "ElseIf Ready"
        }) {
            assertEquals(1, PapyrusEnterIndentHandler.indentChange(line), line);
        }
        assertEquals(0, PapyrusEnterIndentHandler.indentChange("String Property Label Auto"));
        assertEquals(0, PapyrusEnterIndentHandler.indentChange("Function Test() Native"));
    }

    @Test
    void matchesExactUpstreamDecreaseRules() {
        for (String line : new String[]{
                "EndIf",
                "EndWhile",
                "EndProperty",
                "EndStruct",
                "EndGroup",
                "EndState",
                "EndEvent",
                "EndFunction"
        }) {
            assertEquals(-1, PapyrusEnterIndentHandler.indentChange(line), line);
        }
        assertEquals(0, PapyrusEnterIndentHandler.indentChange("Debug.Trace(\"x\")"));
    }

    @Test
    void calculatesAndGeneratesIndentDeterministically() {
        assertEquals(8, PapyrusEnterIndentHandler.visualIndent("\t    If Ready", 4));
        assertEquals("        ", PapyrusEnterIndentHandler.makeIndent(8, 4, false));
        assertEquals("\t\t", PapyrusEnterIndentHandler.makeIndent(8, 4, true));
        assertEquals("\t  ", PapyrusEnterIndentHandler.makeIndent(6, 4, true));
    }
}
