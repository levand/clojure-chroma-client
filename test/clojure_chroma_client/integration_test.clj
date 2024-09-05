(ns clojure-chroma-client.integration-test
  (:require [clojure-chroma-client.api :as api]
            [clojure-chroma-client.config :as cfg]
            [clojure.test :as test :refer [testing deftest is run-tests]])
  (:import [java.util UUID]))

(deftest heartbeat
  (is @(api/heartbeat)))

(deftest version
  (is (string? @(api/version))))

(defn delete-all-collections
  []
  (doseq [coll (api/page-seq (api/collections))]
    @(api/delete-collection coll)))

(deftest collection-manipulation
  (delete-all-collections)
  (let [collections (for [n (range 20)]
                      @(api/create-collection (str "integration-test-" n) {}))]
    (doall collections)
    (testing "Creation is idempotent"
      (let [new @(api/create-collection "integration-test-0" {})]
        (is (= (:id new) (:id (first collections))))))
    (testing "get-collections in one page"
      (let [results @(api/collections)]
        (is (= (set (map :id results))
              (set (map :id collections))))))
    (testing "get-collections in multiple pages"
      (let [results (api/page-seq (api/collections :limit 5))]
        (is (= (set (map :id results))
               (set (map :id collections))))))
    (testing "count and delete-collection"
      (is (= 20 @(api/count-collections)))
      @(api/delete-collection (first collections))
      (is (= 19 @(api/count-collections)))
      (let [colls (set (map :id @(api/collections)))]
        (is (not (contains? colls (:id (first collections)))))))))

(deftest collection-get-and-update
  @(api/delete-collection @(api/create-collection "integration-test-update2"))
  @(api/create-collection "integration-test-updates" :metadata {:foo "foo"})
  (let [c @(api/get-collection "integration-test-updates")]
    (is (= "foo" (:foo (:metadata c))))
    (is (not (:bar (:metadata c))))
    (testing "updates"
      @(api/update-collection c
         :name "integration-test-update2"
         :metadata {:foo "foo2" :bar "bar"})
      (let [c2 @(api/get-collection "integration-test-update2")]
        (is (= "foo2" (:foo (:metadata c2))))
        (is (= "bar" (:bar (:metadata c2))))))))

(defn rand-vector
  [dim]
  (into-array (repeatedly dim rand)))

(defn rand-embedding
  [idx dim metas?]
  (let [record {:id (str "embedding-" idx)
                :embedding (rand-vector dim)}]
    (if metas?
      (merge record {:metadata {:foo (str "foo-" idx)
                                :bar idx
                                :nonce (rand-int 10000)}
                     :document (str "hello foo bar val" idx)})
      record)))

(deftest get-embeddings
  @(api/delete-collection @(api/create-collection "integration-test-embeddings"))
  (let [c @(api/create-collection "integration-test-embeddings")]
    (is (= 0 @(api/count c)))
    @(api/add c (map #(rand-embedding % 256 true) (range 10)))
    (is (= 10 @(api/count c)))
    (is (= 10 (count @(api/get c))))
    (testing "fetch by ID"
      (is (= 2 (count @(api/get c :ids #{"embedding-1" "embedding-4"})))))
    (testing "fetch by $eq"
      (is (= 1 (count @(api/get c :where {:bar 2})))))
    (testing "fetch by $gt"
      (is (= 5 (count @(api/get c :where {:bar {"$gt" 4}})))))
    (testing "fetch by $or"
      (is (= 2 (count @(api/get c :where {:$or [{:bar {"$eq" 1}}
                                                         {:foo {"$eq" "foo-2"}}]})))))
    (testing "fetch by $and"
      (is (= 0 (count @(api/get c :where {:$and [{:bar {"$eq" 1}}
                                                 {:foo {"$eq" "foo-2"}}]}))))
      (is (= 1 (count @(api/get c :where {:$and [{:bar {"$gt" 1}}
                                                {:foo {"$eq" "foo-2"}}]})))))
    (testing "fulltext search"
      (is (= 0 (count @(api/get c :where-document {"$contains" "horse"}))))
      (is (= 1 (count @(api/get c :where-document {"$contains" "val2"}))))
      (testing "boolean operators"
        (is (= 1 (count @(api/get c :where-document {"$and"
                                                     [{"$contains" "val2"}
                                                      {"$contains" "hello"}]}))))
        (is (= 2 (count @(api/get c :where-document {"$or"
                                                     [{"$contains" "val2"}
                                                      {"$contains" "val3"}]}))))))))

(deftest update-and-upsert
  @(api/delete-collection @(api/create-collection "integration-test-updates"))
  (let [c @(api/create-collection "integration-test-updates")]
    @(api/add c (map #(rand-embedding % 2 true) (range 10)))
    (testing "upsert"
      (let [before @(api/get c :ids ["embedding-1"] :include #{:metadatas :documents :embeddings})
            _ @(api/add c [(rand-embedding 1 2 true)] :upsert? false)
            after @(api/get c :ids ["embedding-1"] :include #{:metadatas :documents :embeddings})
            _ @(api/add c [(rand-embedding 1 2 false)] :upsert? true)
            after2 @(api/get c :ids ["embedding-1"] :include #{:metadatas :documents :embeddings})]
        (is (= before after))
        (is (= (:metadata (first before)) (:metadata (first after2))))
        (is (not= (:embedding (first before)) (:embedding (first after2))))))
    (testing "update"
      (let [before @(api/get c :ids ["embedding-2"] :include #{:metadatas :documents :embeddings})
            _ @(api/update c [(rand-embedding 2 2 false)])
            after @(api/get c :ids ["embedding-2"] :include  #{:metadatas :documents :embeddings})]
        (is (= (:metadata (first before)) (:metadata (first after))))
        (is (not= (:embedding (first before)) (:embedding (first after))))))))

(deftest add-batches
  @(api/delete-collection @(api/create-collection "integration-test-add-batch"))
  (let [c @(api/create-collection "integration-test-add-batch")
        embeddings1 (map #(rand-embedding % 2 true) (range 100))
        embeddings2 (map #(rand-embedding % 2 true) (range 100 200))]
    @(api/add-batches c embeddings1 :batch-size 12 :parallel 1)
    (is (= 100 @(api/count c)))
    @(api/add-batches c embeddings2 :batch-size 3 :parallel 5)
    (is (= 200 @(api/count c)))))

(defn- wait-for
  [f]
  (loop [tries 0]
    (when-not (f)
      (when (< tries 3)
        (Thread/sleep 1)
        (recur (inc tries))))))

(deftest delete
  @(api/delete-collection @(api/create-collection "integration-test-deletes"))
  (let [c @(api/create-collection "integration-test-deletes")]
    @(api/add c (map #(rand-embedding % 2 true) (range 10)))
    (let [before @(api/get c)]
      (is (= 10 (count before)))
      (api/delete c :ids ["embedding-0"])
      (wait-for #(= 9 @(api/count c))) ;; Local Chroma is eventually consistent
      (is (= 9 @(api/count c)))
      (is (= 9 (count @(api/get c))))
      @(api/delete c :where {:bar {"$lt" 5}})
      (wait-for #(= 5 @(api/count c))) ;; Local Chroma is eventually consistent
      (is (= 5 @(api/count c)))
      (is (= (drop 5 before) @(api/get c))))))

(deftest query
  @(api/delete-collection @(api/create-collection "integration-test-query"))
  (let [c @(api/create-collection "integration-test-query")
        vectors (repeatedly 3 #(rand-vector 32))]
    @(api/add c (map #(rand-embedding % 32 true) (range 100)))
    (let [r1 @(api/query-batch c vectors)
          r2 @(api/query-batch c vectors)]
      (is (= r1 r2))
      (testing "with filters"
        (let [r3 @(api/query-batch c vectors :where {:bar {"$lt" 50}})]
          (doseq [r r3]
            (is (every? #(< % 50) (map #(:bar (:metadata %)) r))))))
      (testing "with doc filters"
        (let [r3 @(api/query-batch c vectors :where-document {"$contains" "horse"})]
          (doseq [r r3]
            (is (empty? r)))))
      (testing "with query-1 filters"
        (let [r4 @(api/query c (second vectors))]
          (is (= r4 (second r1))))))))
