(ns fukan.common.extraction.lexicon-vocabularies-test
  "Several vocabularies, read by the Lexicon producer: a name has one owner, and a vocabulary a
   project uses from a library is read off the classpath as forms."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fukan.common.extraction.core :as tc]
            [fukan.common.extraction.lexicon :as lx]
            [fukan.cozo.build :as build]))

(defn- refusal
  "The message `extract-roots` throws over the fixture `dir`, or nil when it returns."
  [dir]
  (try (lx/extract-roots [(str "test/fixtures/lexicon_owners/" dir)]) nil
       (catch clojure.lang.ExceptionInfo e (ex-message e))))

(deftest a-name-has-one-owner
  (testing "two vocabularies listing the same var"
    (let [m (refusal "same_var")]
      (is (some? m))
      (is (str/includes? m "shape thing"))
      (is (str/includes? m "vocabulary one listing same-var.shapes/Thing"))
      (is (str/includes? m "vocabulary two listing same-var.shapes/Thing"))))
  (testing "two vocabularies listing two vars whose keys share a namespace"
    (let [m (refusal "two_vars")]
      (is (some? m))
      (is (str/includes? m "vocabulary one listing two-vars.shapes/Thing"))
      (is (str/includes? m "vocabulary two listing two-vars.shapes/AlsoThing"))))
  (testing "two vocabularies carrying the same name"
    (let [m (refusal "same_name")]
      (is (some? m))
      (is (str/includes? m "vocabulary same"))
      (is (str/includes? m "same-name.one/vocabulary"))
      (is (str/includes? m "same-name.two/vocabulary"))))
  (testing "two calls in one def carrying the same name"
    (let [m (refusal "one_def_same_name")]
      (is (some? m))
      (is (str/includes? m "vocabulary same"))
      (is (str/includes? m "vocabularies.clj:13"))
      (is (str/includes? m "vocabularies.clj:14")))))

(deftest a-view-name-has-one-owner
  (let [m (refusal "same_view")]
    (is (some? m))
    (is (str/includes? m "view use/summary"))
    (is (str/includes? m "same_view/views.clj:13"))
    (is (str/includes? m "same_view/views.clj:16"))))

(deftest each-call-is-one-vocabulary
  ;; one def holding two calls: the var is shared, the vocabularies are not
  (let [roots (:roots (lx/extract-roots ["test/fixtures/lexicon_owners/one_def"]))
        of    (fn [id] (some (fn [[i iv]] (when (= id i) iv)) roots))
        owns  (fn [id] (->> (of id) :clauses (some #(when (= :declares-shape (:rk %)) %))
                            :targets (mapv :id)))]
    (is (= #{"things" "others"}
           (set (for [[_ iv] roots :when (= ::lx/Vocabulary (:tag iv))] (:name iv)))))
    (is (= [":thing"] (owns "vocabulary:things")))
    (is (= [":other"] (owns "vocabulary:others")))))

(deftest a-used-vocabulary-is-found-on-the-classpath
  ;; Only the app is handed to the producer; the library it uses lies under `test/`, which the
  ;; test classpath holds, so this does not depend on where the checkout is.
  (let [roots (:roots (lx/extract-roots ["test/fixtures/lexicon_classpath/app"]))
        of    (fn [tag] (set (for [[_ iv] roots :when (= tag (:tag iv))] (:name iv))))
        edges (fn [id rk] (->> roots (some (fn [[i iv]] (when (= id i) iv))) :clauses
                               (some #(when (= rk (:rk %)) %)) :targets (mapv :id)))]
    (testing "every vocabulary a reached library file declares is a fact, the unused one too, and
              the app's draws on the ones it uses"
      (is (= #{"app" "library" "seating" "staff"} (of ::lx/Vocabulary)))
      (is (= ["vocabulary:library" "vocabulary:seating"] (edges "vocabulary:app" :draws-on)))
      (is (= [":library.clerk"] (edges "vocabulary:staff" :declares-shape)))
      (is (= [":library.ticket"] (edges "vocabulary:library" :declares-shape))))
    (testing "the library's shapes and the views declared beside them are read"
      (is (= #{"visit" "library.ticket" "library.seat" "library.clerk"} (of ::lx/Entity)))
      (is (= #{"library/stub" "staff/badge"} (of ::lx/View)))
      (is (= ["vocabulary:staff"] (edges "view:staff/badge" :declared-in)))
      (is (= [":library.clerk/name"] (edges "view:staff/badge" :selects))))
    (testing "a reference across the two resolves to the library's entity"
      (is (= [":library.ticket"] (edges ":visit/ticket" :refers-to)))))
  (testing "assembled with the app's code, the library's view is declared in the library's
            vocabulary — its namespace is no node, since its code is not extracted — and the
            readings see it"
    (let [app "test/fixtures/lexicon_classpath/app"
          db  (build/model->cozo [] {:roots (into (:roots (tc/extract-roots [app]))
                                                  (:roots (lx/extract-roots [app])))})]
      (is (= #{["library" "library.ticket"] ["staff" "library.clerk"]} (lx/context-map db)))
      (is (= #{["library" "library/stub"]} (lx/attribute-readers db "library.ticket/id")))
      (is (= #{["staff" "staff/badge"]} (lx/attribute-readers db "library.clerk/name"))
          "a view of a vocabulary the app does not use still resolves its vocabulary and attributes"))))
