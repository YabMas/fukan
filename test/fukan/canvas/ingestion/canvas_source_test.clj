(ns fukan.canvas.ingestion.canvas-source-test
  "Discovery is filesystem-based; loading is classpath-based. When they disagree the
   consumer must be told the real cause — the spec dir is not on the classpath — rather
   than a downstream 'failed to load canvas namespace'."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fukan.canvas.ingestion.canvas-source :as cs]))

(deftest cwd-only-spec-dir-warns-about-the-classpath
  (testing "a spec dir present on the filesystem but absent from the classpath names its real cause"
    (let [tmp (doto (java.io.File. (System/getProperty "java.io.tmpdir")
                                   (str "fukan-specs-" (System/currentTimeMillis)))
                (.mkdirs))]
      (try
        (spit (java.io.File. tmp "probe.clj") "(ns fukan-test-probe)")
        (let [dir-name (.getName tmp)
              err (java.io.StringWriter.)]
          ;; the dir exists under java.io.tmpdir, never on the classpath
          (binding [*err* err
                    cs/*spec-dirs* [dir-name]]
            (with-redefs [io/file (fn [& args]
                                  (if (= [dir-name] (vec args))
                                    tmp
                                    ;; the VAR — `#'io/file` is the same var as before the
                                    ;; alias; the fallthrough is unreached by this test's
                                    ;; single-arg call and exists only for shape.
                                    (apply #'io/file args)))]
              (cs/canvas-namespaces)))
          (is (str/includes? (str err) "classpath")
              "the warning must name the classpath as the cause"))
        (finally
          (doseq [f (reverse (file-seq tmp))] (.delete f)))))))
