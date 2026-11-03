package dev.codeverse.model;

import static org.junit.jupiter.api.Assertions.*;

import dev.codeverse.model.Data.FileRevision;
import org.junit.jupiter.api.Test;

class BuildingProfileTest {
  private BuildingProfile profile(String source) {
    return BuildingProfile.analyze(
        new FileRevision("Test.java", "", 1, 1, "Java", "root", "text", source));
  }

  private String fields(int count, String separator) {
    StringBuilder s = new StringBuilder("class Sample {\n");
    for (int i = 0; i < count; i++)
      s.append("int metric").append(i).append(" = ").append(i).append(";").append(separator);
    return s.append("}").toString();
  }

  @Test
  void sourceControlsAllFiveModels() {
    assertEquals(BuildingProfile.Model.HOUSE, profile("class Tiny {}").model());
    assertEquals(
        BuildingProfile.Model.BLOCK,
        profile("int run(int value) {\n" + "value += 1;\n".repeat(30) + "return value;}").model());
    assertEquals(BuildingProfile.Model.COURTYARD, profile(fields(32, "\n")).model());
    assertEquals(BuildingProfile.Model.TERRACES, profile(fields(90, "\n")).model());
    assertEquals(BuildingProfile.Model.TOWER, profile(fields(55, " ")).model());
  }

  @Test
  void commentsWhitespaceAndLiteralContentsDoNotCreateWealth() {
    String source = fields(32, "\n");
    assertEquals(
        profile(source),
        profile("// if for while rich\n" + source + "\n/* if else many identifiers */\n\n"));
    assertEquals(
        profile("String s = \"x\";"), profile("String s = \"if for else many unique words\";"));
    assertEquals(profile(source), profile(source.replace("int ", "    int    ")));
  }

  @Test
  void compactFormattingChangesCompactnessNotRichness() {
    var loose = profile(fields(55, "\n"));
    var dense = profile(fields(55, " "));
    assertEquals(loose.richness(), dense.richness());
    assertTrue(dense.compactness() > loose.compactness());
    assertEquals(loose.tokens(), dense.tokens());
  }

  @Test
  void handlesEmptyAndMultilineLiterals() {
    assertEquals(0, profile("").richness());
    assertEquals(0, profile("// nothing\n/* still nothing */").tokens());
    assertEquals(
        profile("String s = \"\"\"a\nb\"\"\";"),
        profile("String s = \"\"\"if for\nelse while\"\"\";"));
    var python =
        BuildingProfile.analyze(
            new FileRevision("x.py", "", 1, 1, "Python", "", "text", "# if while\nx = 'for else'"));
    assertEquals(3, python.tokens());
  }
}
