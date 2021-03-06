package lo.tr.tools.spellchecker;

import _zem.org.antlr.v4.runtime.Token;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;
import zemberek.core.ScoredItem;
import zemberek.core.turkish.RootAttribute;
import zemberek.lm.LmVocabulary;
import zemberek.lm.NgramLanguageModel;
import zemberek.morphology.TurkishMorphology;
import zemberek.morphology.analysis.InformalAnalysisConverter;
import zemberek.morphology.analysis.SingleAnalysis;
import zemberek.morphology.analysis.WordAnalysis;
import zemberek.morphology.analysis.WordAnalysisSurfaceFormatter;
import zemberek.morphology.analysis.WordAnalysisSurfaceFormatter.CaseType;
import zemberek.morphology.generator.WordGenerator;
import zemberek.morphology.lexicon.RootLexicon;
import zemberek.normalization.TurkishSpellChecker;
import zemberek.tokenization.TurkishTokenizer;

public class ZemberekSpellChecker {

  public static ZemberekSpellChecker instance = defaultInstance();

  private TurkishMorphology morphology;
  private TurkishSpellChecker spellChecker;
  private NgramLanguageModel uniGramLanguageModel;
  private InformalAnalysisConverter informalConverter;

  private static ZemberekSpellChecker defaultInstance() {
    TurkishMorphology morphology = TurkishMorphology.builder()
        .setLexicon(RootLexicon.getDefault())
        .useInformalAnalysis().build();
    return new ZemberekSpellChecker(morphology);
  }

  public static ZemberekSpellChecker getInstance() {
    return instance;
  }

  private ZemberekSpellChecker(TurkishMorphology morphology) {
    this.morphology = morphology;
    try {
      this.spellChecker = new TurkishSpellChecker(morphology);
      // add a predicate to the spell checker
      // so that informal or out of official Turkish dictionary words are not allowed.
      this.spellChecker.setAnalysisPredicate(
          a -> !a.getDictionaryItem()
              .hasAnyAttribute(RootAttribute.Ext, RootAttribute.Informal)
              && !a.containsInformalMorpheme());
      this.uniGramLanguageModel = spellChecker.getUnigramLanguageModel();
      this.informalConverter = new InformalAnalysisConverter(morphology.getWordGenerator());
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /**
   * This is used for debugging purposes.
   */
  static synchronized ZemberekSpellChecker getInstance(RootLexicon lexicon) {
    TurkishMorphology morphology = TurkishMorphology.builder()
        .setLexicon(lexicon)
        .useInformalAnalysis().build();
    return new ZemberekSpellChecker(morphology);
  }


  public boolean isCorrect(String w) {

    if (w == null || w.isEmpty()) {
      return true;
    }

    if (w.length() == 1) {
      return true;
    }

    String input = removePunctuation(w);
    int indexOfDash = input.indexOf("-");
    if (indexOfDash != -1) {
      String w1 = input.substring(0, indexOfDash);
      String w2 = input.substring(indexOfDash + 1);
      if (spellChecker.check(w1) && spellChecker.check(w2)) {
        return true;
      }
    }

    boolean passed = spellChecker.check(input);
    if (passed) {
      return true;
    }

    List<Token> tokens = TurkishTokenizer.DEFAULT.tokenize(w);
    if (tokens.size() != 1) {
      return false;
    }
    Token t = tokens.get(0);
    List<SingleAnalysis> analyses = morphology.getUnidentifiedTokenAnalyzer().analyze(t);
    return analyses.size() > 0;

  }

  public List<String> getSuggestions(String s) {

    LinkedHashSet<String> suggestions = new LinkedHashSet<>();
    String word = removePunctuation(s);
    suggestions.addAll(informalWordSuggestions(word));
    suggestions.addAll(spellChecker.suggestForWord(word));
    suggestions.addAll(splitWordSuggestions(s));
    List<String> result = new ArrayList<>(suggestions);
    if (result.size() > 9) {
      return result.subList(0, 9);
    }
    return result;
  }

  private static WordAnalysisSurfaceFormatter formatter = new WordAnalysisSurfaceFormatter();

  private List<String> informalWordSuggestions(String s) {

    CaseType caseType = formatter.guessCase(s);

    WordAnalysis a = morphology.analyze(s);
    if (a.analysisCount() == 0) {
      return Collections.emptyList();
    }
    List<String> result = new ArrayList<>(1);
    for (SingleAnalysis analysis : a) {
      if (analysis.containsInformalMorpheme()) {
        WordGenerator.Result res = informalConverter.convert(s, analysis);
        String apostrophe = getApostrophe(s);

        if (formatter.canBeFormatted(analysis, caseType)) {
          String formatted = formatter.formatToCase(res.analysis, caseType, apostrophe);
          result.add(formatted);
        } else {
          result.add(res.surface);
        }
      }
    }
    return result;
  }

  private String getApostrophe(String input) {
    if (input.indexOf('’') > 0) {
      return "’";
    } else if (input.indexOf('\'') > 0) {
      return "'";
    }
    return null;
  }

  private String removePunctuation(String s) {
    return s.replaceAll("\\p{Punct}+$", "");
  }

  private List<String> splitWordSuggestions(String s) {

    // Prevent small or large inputs.
    if (s.length() < 3 || s.length() > 25) {
      return Collections.emptyList();
    }

    // Apply brute force splitting, and use uni-gram probabilities for ranking multiple scores.
    // Normally using a higher order language model would be the correct approach
    // for ranking but that is not available.
    List<ScoredItem<String>> suggestions = new ArrayList<>(3);
    LmVocabulary vocabulary = uniGramLanguageModel.getVocabulary();

    for (int i = 1; i < s.length() - 1; i++) {
      String s1 = s.substring(0, i);
      String s2 = s.substring(i);
      if (isCorrect(s1) && isCorrect(s2)) {
        float p1 = uniGramLanguageModel.getProbability(vocabulary.indexOf(s1));
        float p2 = uniGramLanguageModel.getProbability(vocabulary.indexOf(s2));
        suggestions.add(new ScoredItem<>(s1 + " " + s2, p1 + p2));
      }
    }

    if (suggestions.size() == 0) {
      return Collections.emptyList();
    }

    // Sort with scores. Higher scored item comes first.
    suggestions.sort((a, b) -> Float.compare(b.score, a.score));

    // Only top 3
    if (suggestions.size() > 3) {
      suggestions = suggestions.subList(0, 3);
    }

    return suggestions.stream().map(a -> a.item).collect(Collectors.toList());
  }

}
