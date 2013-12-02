(ns hyzhenhok.toy
  (:require [hyzhenhok.util :refer :all]
            [hyzhenhok.db :as db]
            [datomic.api :as d]))

;;;; This namespace contains convenient entities to play
;;;; with while I write code.

(defn first-addr []
  (-> (db/find-addr "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa")
      d/touch))

;; (Coinbase is spent)
(defn first-spent-tx []
  (-> "0437cd7f8525ceed2324359c2d0ba26006d92d856a9c20fa0241106ee5a597c9"
      db/find-txn-by-hash
      d/touch))

(defn unspent-txout []
  (-> "4a5e1e4baab89f3a32518a88c31bc87f618f76673e2cc77ab2127b7afdeda33b"
      db/find-txn-by-hash
      :txn/txOuts
      first
      d/touch))

(defn first-spent-txout []
  (-> (first-spent-tx)
      :txn/txOuts
      first
      d/touch))
