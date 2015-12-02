package course2.module2.solns

import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.broadcast.Broadcast
import util.{Files, StopWords}

/**
 * Inverted Index - Basis of Search Engines.
 * Builds on the exercise solution that sorts by words and sorts the list of
 * (file_name, count) values by count descending. This version also filters
 * stop words, propagated as a Broadcast Variable.
 */
object InvertedIndexSortByWordsAndCountsWithStopWordsFiltering {
  def main(args: Array[String]): Unit = {

    val inpath  = "output/crawl"
    val outpath = "output/inverted-index-sorted-stop-words-removed"
    Files.rmrf(outpath)  // delete old output (DON'T DO THIS IN PRODUCTION!)

    val sc = new SparkContext("local[*]", "Inverted Index - Sort by Word & Counts, Remove Stop Words")
    val stopWords: Broadcast[Set[String]] = sc.broadcast(StopWords.words)

    try {
      // Load the input "crawl" data, where each line has the format:
      //   (document_id, text)
      // We use a regular expression to match on and ignore the outer
      // parentheses and use two "capture groups", one for the text between
      // between any initial whitespace and the first comma, and the second
      // group for the remaining text to the end of the line (trimming any
      // whitespace at the end. If the regex matches a line, we return a
      // tuple with the "name" (trimmed of whitespace) and the text, converted
      // to lower case.
      // NOTE: The inpath is a directory; Spark finds the correct data files,
      // named `part-NNNNN`.
      val lineRE = """^\s*\(([^,]+),(.*)\)\s*$""".r
      val input = sc.textFile(inpath) map {
        case lineRE(name, text) => (name.trim, text.toLowerCase)
        case badLine =>
          Console.err.println(s"Unexpected line: $badLine")
          // We have to return a tuple. It will be filtered out by subsequent
          // remove of whitespace (when we split into words).
          ("", "")
      }

      println(s"Writing output to: $outpath")

      // New: See filtering below.
      val numbersRE = """^\d+$""".r
      // Split on non-alphanumeric sequences of character as before.
      // Rather than map to "(word, 1)" tuples, we treat the words by values
      // and count the unique occurrences.
      input
        .flatMap {
          case (path, text) =>
            // If we don't trim leading whitespace, the regex split creates
            // an undesired leading "" word! Also, setup the (word,path) as
            // the key for reducing, and an initial count of 1.
            // Use a refined regex to retain abbreviations, e.g., "there's".
            text.trim.split("""[^\w']""") map (word => ((word, path), 1))
        }
        // New: Filter stop words. Also remove pure numbers.
        // Note that the regular expression match is fairly
        // expensive, so maybe it's not worth doing here!
        .filter {
          case ((word, _), _) =>
            stopWords.value.contains(word) == false && numbersRE.findFirstIn(word) == None
        }
        .reduceByKey {
          // No "case" here, because we don't need to pattern match, as the
          // function takes two arguments, not one argument that needs to be
          // "deconstructed".
          case (count1, count2) => count1 + count2
        }
        .map {
          // Set up for the final output with words as keys. Note how elegant
          // and concise this code is!!
          case ((word, path), n) => (word, (path, n))
        }
        .groupByKey  // The words are the keys
        // New: sort by Key (word).
        .sortByKey(ascending = true)
        .mapValues { iterable =>
            // New: sort the sequence by count, descending. Note that we also
            // sort by path. This is NOT necessary, but it removes randomness
            // when two "n" values are equal! It adds overhead, though.
            val path = iterable.to[Vector].sortBy {
              case (path, n) => (-n, path)
            }
            path.mkString(", ")
        }
        // This also works, but is slightly more expensive when you don't
        // change the key:
        // .map {
        //   case (word, iterable) =>
        //     val path = iterable.to[Vector].sortBy {
        //       case (path, n) => (-n, path)
        //     }
        //     (word, path.mkString(", "))
        // }
        .saveAsTextFile(outpath)
    } finally {
      sc.stop()
    }
  }
}
