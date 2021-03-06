;; copyright (c) 2019-2020 Sean Corfield, all rights reserved

(ns next.jdbc-test
  "Basic tests for the primary API of `next.jdbc`."
  (:require [clojure.core.reducers :as r]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as c]
            [next.jdbc.test-fixtures
             :refer [with-test-db db ds column
                      default-options stored-proc?
                      derby? hsqldb? jtds? mssql? mysql? postgres?]]
            [next.jdbc.prepare :as prep]
            [next.jdbc.result-set :as rs]
            [next.jdbc.specs :as specs])
  (:import (java.sql ResultSet)))

(set! *warn-on-reflection* true)

;; around each test because of the folding tests using 1,000 rows
(use-fixtures :each with-test-db)

(specs/instrument)

(deftest basic-tests
  ;; use ds-opts instead of (ds) anywhere you want default options applied:
  (let [ds-opts (jdbc/with-options (ds) (default-options))]
    (testing "plan"
      (is (= "Apple"
             (reduce (fn [_ row] (reduced (:name row)))
                     nil
                     (jdbc/plan
                      ds-opts
                      ["select * from fruit where appearance = ?" "red"]))))
      (is (= "Banana"
             (reduce (fn [_ row] (reduced (:no-such-column row "Banana")))
                     nil
                     (jdbc/plan
                      ds-opts
                      ["select * from fruit where appearance = ?" "red"])))))
    (testing "execute-one!"
      (is (nil? (jdbc/execute-one!
                 (ds)
                 ["select * from fruit where appearance = ?" "neon-green"])))
      (is (= "Apple" ((column :FRUIT/NAME)
                      (jdbc/execute-one!
                       ds-opts
                       ["select * from fruit where appearance = ?" "red"]))))
      (is (= "red" (:fruit/looks-like
                    (jdbc/execute-one!
                     ds-opts
                     ["select appearance as looks_like from fruit where id = ?" 1]
                     jdbc/snake-kebab-opts))))
      (is (= "red" (:looks-like
                    (jdbc/execute-one!
                     ds-opts
                     ["select appearance as looks_like from fruit where id = ?" 1]
                     jdbc/unqualified-snake-kebab-opts)))))
    (testing "execute!"
      (let [rs (jdbc/execute!
                ds-opts
                ["select * from fruit where appearance = ?" "neon-green"])]
        (is (vector? rs))
        (is (= [] rs)))
      (let [rs (jdbc/execute!
                ds-opts
                ["select * from fruit where appearance = ?" "red"])]
        (is (= 1 (count rs)))
        (is (= 1 ((column :FRUIT/ID) (first rs)))))
      (let [rs (jdbc/execute!
                ds-opts
                ["select * from fruit order by id"]
                {:builder-fn rs/as-maps})]
        (is (every? map? rs))
        (is (every? meta rs))
        (is (= 4 (count rs)))
        (is (= 1 ((column :FRUIT/ID) (first rs))))
        (is (= 4 ((column :FRUIT/ID) (last rs)))))
      (let [rs (jdbc/execute!
                ds-opts
                ["select * from fruit order by id"]
                {:builder-fn rs/as-arrays})]
        (is (every? vector? rs))
        (is (= 5 (count rs)))
        (is (every? #(= 5 (count %)) rs))
        ;; columns come first
        (is (every? qualified-keyword? (first rs)))
        ;; :FRUIT/ID should be first column
        (is (= (column :FRUIT/ID) (ffirst rs)))
        ;; and all its corresponding values should be ints
        (is (every? int? (map first (rest rs))))
        (is (every? string? (map second (rest rs))))))
    (testing "execute! with adapter"
      (let [rs (jdbc/execute! ; test again, with adapter and lower columns
                ds-opts
                ["select * from fruit order by id"]
                {:builder-fn (rs/as-arrays-adapter
                              rs/as-lower-arrays
                              (fn [^ResultSet rs _ ^Integer i]
                                (.getObject rs i)))})]
        (is (every? vector? rs))
        (is (= 5 (count rs)))
        (is (every? #(= 5 (count %)) rs))
        ;; columns come first
        (is (every? qualified-keyword? (first rs)))
        ;; :fruit/id should be first column
        (is (= :fruit/id (ffirst rs)))
        ;; and all its corresponding values should be ints
        (is (every? int? (map first (rest rs))))
        (is (every? string? (map second (rest rs))))))
    (testing "execute! with unqualified"
      (let [rs (jdbc/execute!
                (ds)
                ["select * from fruit order by id"]
                {:builder-fn rs/as-unqualified-maps})]
        (is (every? map? rs))
        (is (every? meta rs))
        (is (= 4 (count rs)))
        (is (= 1 ((column :ID) (first rs))))
        (is (= 4 ((column :ID) (last rs)))))
      (let [rs (jdbc/execute!
                ds-opts
                ["select * from fruit order by id"]
                {:builder-fn rs/as-unqualified-arrays})]
        (is (every? vector? rs))
        (is (= 5 (count rs)))
        (is (every? #(= 5 (count %)) rs))
        ;; columns come first
        (is (every? simple-keyword? (first rs)))
        ;; :ID should be first column
        (is (= (column :ID) (ffirst rs)))
        ;; and all its corresponding values should be ints
        (is (every? int? (map first (rest rs))))
        (is (every? string? (map second (rest rs))))))
    (testing "execute! with :max-rows / :maxRows"
      (let [rs (jdbc/execute!
                ds-opts
                ["select * from fruit order by id"]
                {:max-rows 2})]
        (is (every? map? rs))
        (is (every? meta rs))
        (is (= 2 (count rs)))
        (is (= 1 ((column :FRUIT/ID) (first rs))))
        (is (= 2 ((column :FRUIT/ID) (last rs)))))
      (let [rs (jdbc/execute!
                ds-opts
                ["select * from fruit order by id"]
                {:statement {:maxRows 2}})]
        (is (every? map? rs))
        (is (every? meta rs))
        (is (= 2 (count rs)))
        (is (= 1 ((column :FRUIT/ID) (first rs))))
        (is (= 2 ((column :FRUIT/ID) (last rs)))))))
  (testing "prepare"
    ;; default options do not flow over get-connection
    (let [rs (with-open [con (jdbc/get-connection (ds))
                         ps  (jdbc/prepare
                              con
                              ["select * from fruit order by id"]
                              (default-options))]
                 (jdbc/execute! ps))]
      (is (every? map? rs))
      (is (every? meta rs))
      (is (= 4 (count rs)))
      (is (= 1 ((column :FRUIT/ID) (first rs))))
      (is (= 4 ((column :FRUIT/ID) (last rs)))))
    ;; default options do not flow over get-connection
    (let [rs (with-open [con (jdbc/get-connection (ds))
                         ps  (jdbc/prepare
                              con
                              ["select * from fruit where id = ?"]
                              (default-options))]
                 (jdbc/execute! (prep/set-parameters ps [4]) nil {}))]
      (is (every? map? rs))
      (is (every? meta rs))
      (is (= 1 (count rs)))
      (is (= 4 ((column :FRUIT/ID) (first rs))))))
  (testing "statement"
    ;; default options do not flow over get-connection
    (let [rs (with-open [con (jdbc/get-connection (ds))]
               (jdbc/execute! (prep/statement con (default-options))
                              ["select * from fruit order by id"]))]
      (is (every? map? rs))
      (is (every? meta rs))
      (is (= 4 (count rs)))
      (is (= 1 ((column :FRUIT/ID) (first rs))))
      (is (= 4 ((column :FRUIT/ID) (last rs)))))
    ;; default options do not flow over get-connection
    (let [rs (with-open [con (jdbc/get-connection (ds))]
               (jdbc/execute! (prep/statement con (default-options))
                              ["select * from fruit where id = 4"]))]
      (is (every? map? rs))
      (is (every? meta rs))
      (is (= 1 (count rs)))
      (is (= 4 ((column :FRUIT/ID) (first rs))))))
  (testing "transact"
    (is (= [{:next.jdbc/update-count 1}]
           (jdbc/transact (ds)
                          (fn [t] (jdbc/execute! t ["
INSERT INTO fruit (name, appearance, cost, grade)
VALUES ('Pear', 'green', 49, 47)
"]))
                          {:rollback-only true})))
    (is (= 4 (count (jdbc/execute! (ds) ["select * from fruit"])))))
  (testing "with-transaction rollback-only"
    (is (= [{:next.jdbc/update-count 1}]
           (jdbc/with-transaction [t (ds) {:rollback-only true}]
             (jdbc/execute! t ["
INSERT INTO fruit (name, appearance, cost, grade)
VALUES ('Pear', 'green', 49, 47)
"]))))
    (is (= 4 (count (jdbc/execute! (ds) ["select * from fruit"]))))
    (with-open [con (jdbc/get-connection (ds))]
      (let [ac (.getAutoCommit con)]
        (is (= [{:next.jdbc/update-count 1}]
               (jdbc/with-transaction [t con {:rollback-only true}]
                 (jdbc/execute! t ["
INSERT INTO fruit (name, appearance, cost, grade)
VALUES ('Pear', 'green', 49, 47)
"]))))
        (is (= 4 (count (jdbc/execute! con ["select * from fruit"]))))
        (is (= ac (.getAutoCommit con))))))
  (testing "with-transaction exception"
    (is (thrown? Throwable
           (jdbc/with-transaction [t (ds)]
             (jdbc/execute! t ["
INSERT INTO fruit (name, appearance, cost, grade)
VALUES ('Pear', 'green', 49, 47)
"])
             (throw (ex-info "abort" {})))))
    (is (= 4 (count (jdbc/execute! (ds) ["select * from fruit"]))))
    (with-open [con (jdbc/get-connection (ds))]
      (let [ac (.getAutoCommit con)]
        (is (thrown? Throwable
               (jdbc/with-transaction [t con]
                 (jdbc/execute! t ["
INSERT INTO fruit (name, appearance, cost, grade)
VALUES ('Pear', 'green', 49, 47)
"])
                 (throw (ex-info "abort" {})))))
        (is (= 4 (count (jdbc/execute! con ["select * from fruit"]))))
        (is (= ac (.getAutoCommit con))))))
  (testing "with-transaction call rollback"
    (is (= [{:next.jdbc/update-count 1}]
           (jdbc/with-transaction [t (ds)]
             (let [result (jdbc/execute! t ["
INSERT INTO fruit (name, appearance, cost, grade)
VALUES ('Pear', 'green', 49, 47)
"])]
               (.rollback t)
               result))))
    (is (= 4 (count (jdbc/execute! (ds) ["select * from fruit"]))))
    (with-open [con (jdbc/get-connection (ds))]
      (let [ac (.getAutoCommit con)]
        (is (= [{:next.jdbc/update-count 1}]
               (jdbc/with-transaction [t con]
                 (let [result (jdbc/execute! t ["
INSERT INTO fruit (name, appearance, cost, grade)
VALUES ('Pear', 'green', 49, 47)
"])]
                   (.rollback t)
                   result))))
        (is (= 4 (count (jdbc/execute! con ["select * from fruit"]))))
        (is (= ac (.getAutoCommit con))))))
  (testing "with-transaction with unnamed save point"
    (is (= [{:next.jdbc/update-count 1}]
           (jdbc/with-transaction [t (ds)]
             (let [save-point (.setSavepoint t)
                   result (jdbc/execute! t ["
INSERT INTO fruit (name, appearance, cost, grade)
VALUES ('Pear', 'green', 49, 47)
"])]
               (.rollback t save-point)
               result))))
    (is (= 4 (count (jdbc/execute! (ds) ["select * from fruit"]))))
    (with-open [con (jdbc/get-connection (ds))]
      (let [ac (.getAutoCommit con)]
        (is (= [{:next.jdbc/update-count 1}]
               (jdbc/with-transaction [t con]
                 (let [save-point (.setSavepoint t)
                       result (jdbc/execute! t ["
INSERT INTO fruit (name, appearance, cost, grade)
VALUES ('Pear', 'green', 49, 47)
"])]
                   (.rollback t save-point)
                   result))))
        (is (= 4 (count (jdbc/execute! con ["select * from fruit"]))))
        (is (= ac (.getAutoCommit con))))))
  (testing "with-transaction with named save point"
    (is (= [{:next.jdbc/update-count 1}]
           (jdbc/with-transaction [t (ds)]
             (let [save-point (.setSavepoint t (name (gensym)))
                   result (jdbc/execute! t ["
INSERT INTO fruit (name, appearance, cost, grade)
VALUES ('Pear', 'green', 49, 47)
"])]
               (.rollback t save-point)
               result))))
    (is (= 4 (count (jdbc/execute! (ds) ["select * from fruit"]))))
    (with-open [con (jdbc/get-connection (ds))]
      (let [ac (.getAutoCommit con)]
        (is (= [{:next.jdbc/update-count 1}]
               (jdbc/with-transaction [t con]
                 (let [save-point (.setSavepoint t (name (gensym)))
                       result (jdbc/execute! t ["
INSERT INTO fruit (name, appearance, cost, grade)
VALUES ('Pear', 'green', 49, 47)
"])]
                   (.rollback t save-point)
                   result))))
        (is (= 4 (count (jdbc/execute! con ["select * from fruit"]))))
        (is (= ac (.getAutoCommit con)))))))

(deftest folding-test
  (jdbc/execute-one! (ds) ["delete from fruit"])
  (with-open [con (jdbc/get-connection (ds))
              ps  (jdbc/prepare con ["insert into fruit(name) values (?)"])]
    (prep/execute-batch! ps (mapv #(vector (str "Fruit-" %)) (range 1 1001))))
  (testing "foldable result set"
    (testing "from a Connection"
      (let [result
            (with-open [con (jdbc/get-connection (ds))]
              (r/foldcat
               (r/map (column :FRUIT/NAME)
                      (jdbc/plan con ["select * from fruit order by id"]
                                 (default-options)))))]
        (is (= 1000 (count result)))
        (is (= "Fruit-1" (first result)))
        (is (= "Fruit-1000" (last result)))))
    (testing "from a DataSource"
      (doseq [n [1 2 3 4 5 100 300 500 700 900 1000 1100]]
        (testing (str "folding with n = " n)
          (let [result
                (r/fold n r/cat r/append!
                        (r/map (column :FRUIT/NAME)
                               (jdbc/plan (ds) ["select * from fruit order by id"]
                                          (default-options))))]
            (is (= 1000 (count result)))
            (is (= "Fruit-1" (first result)))
            (is (= "Fruit-1000" (last result)))))))
    (testing "from a PreparedStatement"
      (let [result
            (with-open [con (jdbc/get-connection (ds))
                        stmt (jdbc/prepare con
                                           ["select * from fruit order by id"]
                                           (default-options))]
              (r/foldcat
               (r/map (column :FRUIT/NAME)
                      (jdbc/plan stmt nil (default-options)))))]
        (is (= 1000 (count result)))
        (is (= "Fruit-1" (first result)))
        (is (= "Fruit-1000" (last result)))))
    (testing "from a Statement"
      (let [result
            (with-open [con (jdbc/get-connection (ds))
                        stmt (prep/statement con (default-options))]
              (r/foldcat
               (r/map (column :FRUIT/NAME)
                      (jdbc/plan stmt ["select * from fruit order by id"]
                                 (default-options)))))]
        (is (= 1000 (count result)))
        (is (= "Fruit-1" (first result)))
        (is (= "Fruit-1000" (last result)))))))

(deftest connection-tests
  (testing "datasource via jdbcUrl"
    (when-not (postgres?)
      (let [[url etc] (#'c/spec->url+etc (db))
            ds (jdbc/get-datasource (assoc etc :jdbcUrl url))]
        (cond (derby?) (is (= {:create true} etc))
              (mssql?) (is (= #{:user :password} (set (keys etc))))
              (mysql?) (is (= #{:user :password :useSSL}
                              (disj (set (keys etc)) :disableMariaDbDriver)))
              :else    (is (= {} etc)))
        (is (instance? javax.sql.DataSource ds))
        (is (str/index-of (pr-str ds) (str "jdbc:"
                                           (cond (jtds?)
                                                 "jtds:sqlserver"
                                                 (mssql?)
                                                 "sqlserver"
                                                 :else
                                                 (:dbtype (db))))))
        ;; checks get-datasource on a DataSource is identity
        (is (identical? ds (jdbc/get-datasource ds)))
        (with-open [con (jdbc/get-connection ds {})]
          (is (instance? java.sql.Connection con)))))))

(deftest multi-rs
  (when (mssql?)
    (testing "script with multiple result sets"
      (let [multi-rs
            (jdbc/execute! (ds)
                           [(str "begin"
                                 " select * from fruit;"
                                 " select * from fruit where id < 4;"
                                 " end")]
                           {:multi-rs true})]
        (is (= 2 (count multi-rs)))
        (is (= 4 (count (first multi-rs))))
        (is (= 3 (count (second multi-rs)))))))
  (when (stored-proc?)
    (testing "stored proc; multiple result sets"
      (try
        (let [multi-rs
              (jdbc/execute! (ds)
                             [(if (mssql?) "EXEC FRUITP" "CALL FRUITP()")]
                             {:multi-rs true})
              zero-updates [{:next.jdbc/update-count 0}]]
          (cond (postgres?) ; does not support multiple result sets yet
                (do
                  (is (= 1 (count multi-rs)))
                  (is (= zero-updates (first multi-rs))))
                (hsqldb?)
                (do
                  (is (= 3 (count multi-rs)))
                  (is (= zero-updates (first multi-rs))))
                (mysql?)
                (do
                  (is (= 3 (count multi-rs)))
                  (is (= zero-updates (last multi-rs))))
                :else
                (is (= 2 (count multi-rs)))))
        (catch Throwable t
          (println 'call-proc (:dbtype (db)) (ex-message t) (some-> t (ex-cause) (ex-message))))))))

(deftest plan-misuse
  (let [s (pr-str (jdbc/plan (ds) ["select * from fruit"]))]
    (is (re-find #"missing reduction" s)))
  (let [s (pr-str (into [] (jdbc/plan (ds) ["select * from fruit"])))]
    (is (re-find #"missing `map` or `reduce`" s)))
  ;; this may succeed or not, depending on how the driver handles things
  ;; most drivers will error because the ResultSet was closed before pr-str
  ;; is invoked (which will attempt to print each row)
  (let [s (pr-str (into [] (take 3) (jdbc/plan (ds) ["select * from fruit"]
                                               (default-options))))]
    (is (or (re-find #"missing `map` or `reduce`" s)
            (re-find #"(?i)^\[#:fruit\{.*:id.*\}\]$" s))))
  (is (every? #(re-find #"(?i)^#:fruit\{.*:id.*\}$" %)
              (into [] (map str) (jdbc/plan (ds) ["select * from fruit"]
                                            (default-options)))))
  (is (every? #(re-find #"(?i)^#:fruit\{.*:id.*\}$" %)
              (into [] (map pr-str) (jdbc/plan (ds) ["select * from fruit"]
                                               (default-options)))))
  (is (thrown? IllegalArgumentException
               (doall (take 3 (jdbc/plan (ds) ["select * from fruit"]))))))
