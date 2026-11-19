(ns canvas.claims.vocabularies
  "What reading a system written in several Lexicon vocabularies commits fukan's extraction to: a
   vocabulary is a fact, a name has one owner, a library contributes its words and not its code,
   and where two languages meet is read, not judged. Each Claim's name is the id the design record
   carries for it and its docstring is the record's statement."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.architecture.ingestion.extraction :as extraction]
            [canvas.architecture.orchestration.cli :as cli]))

(Claim a-vocabulary-is-a-fact
  "Each lexicon.core/vocabulary call the producer reads is one Vocabulary fact, named by its :name
   or else by the var holding it. It declares exactly the shapes its call lists and draws on
   exactly the vocabularies its :uses names, so every Entity and Value in the model is declared by
   exactly one Vocabulary."
  {:about [extraction/extraction] :evidence "round"})

(Claim one-owner-per-name
  "A name has one owner. The producer throws, naming both declarations, when two vocabularies
   declare a shape of the same name, whether they list the same var or two different ones, and
   when two vocabularies carry the same name. It never merges them into one node."
  {:about [extraction/extraction] :evidence "round"})

(Claim used-vocabulary-read-as-forms
  "A vocabulary named by :uses is followed to a fixpoint. If it is not under the source paths, its
   namespace is found as a classpath resource and read as forms, like a file under the paths;
   nothing is loaded, and the views it contributes are the ones declared in that file. A used
   vocabulary that cannot be found throws."
  {:about [extraction/extraction] :evidence "round"})

(Claim words-of-a-dependency-not-its-code
  "What a project takes from a library found on the classpath is its words: the used vocabulary's
   shapes, attributes and the views declared in its own file enter the model, and the library's
   code does not. Code facts are produced only from the paths the producers are handed (in a CLI
   build, its one --src). The reference test hands reference/shop/src and reference/web/src to
   both producers, so there web's code is extracted as code under the paths."
  {:about [extraction/extraction cli/cli] :evidence "round"})

(Claim where-languages-meet-is-a-reading
  "Which vocabulary a signature speaks is a reading over facts: it speaks a vocabulary when it
   takes or gives an attribute of a shape that vocabulary declares. So is where two vocabularies
   meet: the functions whose signatures speak more than one. No law judges which code may speak
   which vocabulary."
  {:about [extraction/extraction] :evidence "round"})

(Claim producer-adds-facts
  "What the producer contributes to a build is facts: Vocabulary, Entity, Value, Attribute, View
   and ViewUse roots. Its readings (domain-map, context-map, footprint, unread-claims,
   attribute-readers, languages, translators) are queries. lexicon.clj authors no law; the laws
   over those facts are the ones the kernel generates from the fact structures' slot declarations."
  {:about [extraction/extraction] :evidence "round"})

(Claim reference-proves-several-vocabularies
  "fukan.reference-shop-test passes over reference/shop/src and reference/web/src and asserts
   exactly two Vocabulary facts, shop and web, each owning its own shapes; that shop draws on web;
   that the reference login → web.session is in the domain map; and that lx/languages and
   lx/translators return exactly the rows the test states, only shop.front's two functions
   speaking both."
  {:about [extraction/extraction] :evidence "round"})

(Claim collision-refused-in-a-fixture
  "A fukan-side test with its own fixture shows the producer throws when one shape is declared by
   two vocabularies, with a message naming both declarations."
  {:about [extraction/extraction] :evidence "round"})

(Claim classpath-vocabulary-in-a-fixture
  "A fukan-side test, independent of the checkout layout, shows a used vocabulary that is not
   under the source paths is found on the classpath and read as forms."
  {:about [extraction/extraction] :evidence "round"})

(Claim lands-clean
  "The change lands with clojure -M:test, clojure -M:lint --lint src common canvas test (only the
   2 known warnings in test/tasks/kondo_test.clj) and clojure -M:dev -m fukan.cli check --src src
   --format text all clean, the declarations golden updated with a dated comment, and clojure
   -M:kondo re-run with its *-file-is-current tests passing."
  {:about [extraction/extraction cli/cli] :evidence "round"})

(Claim several-vocabularies-documented
  "CLAUDE.md, reference/shop/README.md and reference/web/README.md describe several vocabularies,
   name ownership (one owner per namespace, collisions refused), references between vocabularies,
   and the two readings languages and translators."
  {:about [extraction/extraction] :evidence "round"})
