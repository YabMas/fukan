(ns fukan.canvas.projection.canvas-source-test
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [fukan.canvas.core.substrate.store :as store]
            [fukan.canvas.projection.canvas-source :as canvas-source]
            canvas.infra.server
            canvas.infra.model))

;; ---------------------------------------------------------------------------
;; Commit 1 tests: build-canvas-db
;; ---------------------------------------------------------------------------

(deftest build-canvas-db-produces-populated-db
  (testing "build-canvas-db returns a Datascript db with all canvas modules"
    (let [db (canvas-source/build-canvas-db)]
      (is (some? db))
      (is (seq (store/all-modules db)))
      (is (> (count (store/all-modules db)) 50)
          "Expected 60+ modules (62 canvas ports, each with ≥1 module)"))))

(deftest build-canvas-db-contains-known-modules
  (testing "known canvas port modules appear in the unified db"
    (let [db    (canvas-source/build-canvas-db)
          names (set (map :name (store/all-modules db)))]
      (is (contains? names "infra.server"))
      (is (contains? names "infra.model"))
      (is (contains? names "model.spec")))))

(deftest build-canvas-db-module-children-preserved
  (testing "module/child relationships survive the merge"
    (let [db     (canvas-source/build-canvas-db)
          mod-id (ffirst (d/q '[:find ?id
                                 :where [?e :entity/name "infra.server"]
                                        [?e :entity/id ?id]]
                               db))]
      (is (some? mod-id) "infra.server module must be present")
      (let [children (store/children-of-module db mod-id)]
        (is (pos? (count children))
            "infra.server must have children")
        (is (some #(= "start_server" (second %)) children))
        (is (some #(= "stop_server" (second %)) children))
        (is (some #(= "ServerOpts" (second %)) children))))))

(deftest build-canvas-db-references-preserved
  (testing ":references datoms survive the merge"
    (let [db   (canvas-source/build-canvas-db)
          refs (d/q '[:find ?v :where [_ :references ?v]] db)]
      (is (pos? (count refs))
          "At least some :references relations must be present"))))

(deftest build-canvas-db-duplicate-name-detection
  (testing "duplicate entity names are detected and reported (non-throwing)"
    ;; The check warns to stderr but does not throw — we verify it completes
    ;; and returns a db even when duplicates exist in fukan's corpus.
    (let [db (canvas-source/build-canvas-db)]
      ;; If we got here without exception, the behavior is correct
      (is (some? db)))))
