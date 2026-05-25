(ns fukan.canvas.library.closure-test
  (:require [clojure.test :refer [deftest is testing]]
            [fukan.canvas.helpers :as h]
            [fukan.canvas.library.monolith :refer [function record]]
            [fukan.canvas.library.closure :refer [exports]]
            [datascript.core :as d]))

(deftest exports-tags-listed-names
  (testing "(exports …) tags the listed declarations with :exported"
    (let [db (h/with-canvas
               (h/within-module "infra.server"
                 (record "ServerOpts" "Server config." (field port :Integer))
                 (record "ServerInfo" "Server info."  (field uptime :Integer))
                 (function "start_server" "Start the server." (gives :Unit))
                 (exports ServerOpts ServerInfo start_server)))
          tagged (d/q '[:find ?n
                        :where [?e :entity/tag :exported]
                               [?e :entity/name ?n]]
                      db)]
      (is (= #{"ServerOpts" "ServerInfo" "start_server"} (set (map first tagged)))))))

(deftest exports-tolerates-unknown-names
  (testing "exports for a name not declared in this module is a silent no-op"
    ;; Don't want exports to throw on a typo — just doesn't tag anything.
    ;; (A future linter could warn, but the canvas mechanism shouldn't fail.)
    (let [db (h/with-canvas
               (h/within-module "infra.server"
                 (record "ServerOpts" "." (field port :Integer))
                 (exports ServerOpts NonexistentName)))
          tagged (d/q '[:find ?n
                        :where [?e :entity/tag :exported]
                               [?e :entity/name ?n]]
                      db)]
      ;; Only ServerOpts should be tagged
      (is (= #{"ServerOpts"} (set (map first tagged)))))))
