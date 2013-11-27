(ns hyzhenhok.script-test
  (:require [expectations :refer :all]
            [hyzhenhok.script :refer :all]
            [hyzhenhok.util :refer :all]
            [hyzhenhok.db :as db]
            [hyzhenhok.keyx :as key]
            [hyzhenhok.codec2 :as codec2]
            [hyzhenhok.crypto :as crypto]
            [datomic.api :as d]
            [clojure.string :as str]))

(expect :sighash-all (extract-hash-type (hex->bytes "aabbcc01")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Script parsing ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(given [input pretty-output] (expect pretty-output
                               (prettify-output
                                (parse
                                 (hex->bytes
                                  (str/replace input #"\s+" "")))))
  ;; === op-datapush1 (is 0x4c 76) ===
  "4c 01 0a" ["0a"]
  "4c 02 0a0b" ["0a0b"]

  ;; === op-datapush2 (is 0x4d 77) ===
  ;; - take 1 byte
  "4d 0001 0a" ["0a"]
  ;; - take 2 bytes
  "4d 0002 0a0b" ["0a0b"]

  ;; === op-datapush4 (is 0x4e 78) ===
  "4e 00000001 0a" ["0a"]
  "4e 00000002 0a0b" ["0a0b"]


  ;; === 1 thru 75 ===
  ;; Push that many follow bytes
  "01 0a" ["0a"]
  "02 0a0b" ["0a0b"]
  ;; 75 bytes
  "4b 01020304050607080900 01020304050607080900
      01020304050607080900 01020304050607080900
      01020304050607080900 01020304050607080900
      01020304050607080900 0102030405"
  ["010203040506070809000102030405060708090001020304050607080900010203040506070809000102030405060708090001020304050607080900010203040506070809000102030405"]

  ;; === ops ===

  "00" [:op-false]
  "0000" [:op-false :op-false]

  "525354" [:op-2 :op-3 :op-4]
  "030a0b0c 63 67 68" ["0a0b0c" :op-if :op-else :op-endif]

  ;; 171 :op-codeseparator
  "00ab00ab"
  [:op-false :op-codeseparator :op-false :op-codeseparator]

  )

(num->hex 171)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Script execution ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(given [pre-state script post-state]
    (expect (prettify-output2 post-state)
      ;; TODO: Make a better error-handling system during
      ;;       script parse/execute.
      (try (prettify-output2
            (execute script pre-state {}))
           (catch clojure.lang.ExceptionInfo _
           :threw)))

  ;; default
  ['() '() '()] ["someconstant"] ['("someconstant") '() '()]

  ;; 0 :op-false
  ['() '() '()] [:op-false] ['(0) '() '()]
  ;; 79 :op-1negate
  ['() '() '()] [:op-1negate] ['(-1) '() '()]
  ;; 81 :op-true
  ['() '() '()] [:op-true] ['(1) '() '()]

  ['() '() '()] [:op-true :op-1negate] ['(-1 1) '() '()]
  ['() '() '()] [:op-true :op-1negate :op-add] ['(0) '() '()]

  ;; 82 :op-2
  ['() '() '()] [:op-2] ['(2) '() '()]
  ;; .
  ;; .
  ;; .

  ;; === Flow control ===

  ;; 97 :op-nop
  ['() '() '()] [:op-nop] ['() '() '()]
  ;; 99 :op-if
  ['() '() '()] [:op-if] ['() '() '(:true)]
  ;; 100 :op-notif
  ['() '() '()] [:op-notif] ['() '() '(:false)]
  ;; 103 :op-else
  ['() '() '(:true)]    [:op-else] ['() '() '(:drained)]
  ['() '() '(:false)]   [:op-else] ['() '() '(:true)]
  ['() '() '(:drained)] [:op-else] ['() '() '(:drained)]
  ['() '() '()]         [:op-else] :threw
  ;; 104 :op-endif
  ['() '() '(:true)]    [:op-endif] ['() '() '()]
  ['() '() '(:false)]   [:op-endif] ['() '() '()]
  ['() '() '(:drained)] [:op-endif] ['() '() '()]
  ['() '() '()]         [:op-endif] :threw
  ;; 105 :op-verify
  ['() '() '()]  [:op-verify] ['() '() '()]
  ['(1) '() '()] [:op-verify] ['() '() '()]
  ['(0) '() '()] [:op-verify] [:invalid '() '()]
  ;; 106 :op-return
  ['(1) '() '()] [:op-return] [:invalid '() '()]
  ['(0) '() '()] [:op-return] [:invalid '() '()]
  ['() '() '()]  [:op-return] [:invalid '() '()]

  ;; === Stack op ===

  ;; 107 :op-toaltstack
  ['(42) '() '()] [:op-toaltstack] ['() '(42) '()]
  ;; 108 :op-fromaltstack
  ['() '(42) '()] [:op-fromaltstack] ['(42) '() '()]
  ;; 109 :op-2drop
  ['(:a :b :c) '() '()] [:op-2drop] ['(:c) '() '()]
  ;; 110 :op-2dup
  ['(1 2 3) '() '()] [:op-2dup] ['(1 2 1 2 3) '() '()]
  ;; 111 :op-3dup
  ['(1 2 3 4) '() '()] [:op-3dup] ['(1 2 3 1 2 3 4) '() '()]
  ;; 112 :op-2over
  ['(:c :d :a :b :c) '() '()] [:op-2over] ['(:a :b :c) '() '()]
  ;; 113 :op-2rot
  ['(:a :b :c :d :e :f :g) '() '()]
  [:op-2rot]
  ['(:e :f :a :b :c :d :g) '() '()]
  ;; 114 :op-2swap
  ['(:a :b :c :d :e) '() '()]
  [:op-2swap]
  ['(:c :d :a :b :e) '() '()]
  ;; 115 :op-ifdup
  ['(1) '() '()] [:op-ifdup] ['(1 1) '() '()]
  ['(0) '() '()] [:op-ifdup] ['(0) '() '()]
  ;; 116 :op-depth
  ['(:a :b :c) '() '()] [:op-depth] ['(3 :a :b :c) '() '()]
  ;; 117 :op-drop
  ['(:a :b :c) '() '()] [:op-drop] ['(:b :c) '() '()]
  ;; 118 :op-dup
  ['(42) '() '()] [:op-dup] ['(42 42) '() '()]
  ;; 119 :op-nip
  ['(:a :b :c) '() '()] [:op-nip] ['(:a :c) '() '()]
  ;; 120 :op-over
  ['(:a :b :c) '() '()] [:op-over] ['(:b :a :b :c) '() '()]
  ;; 121 :op-pick
  ['(1 :a :b :c) '() '()] [:op-pick] ['(:b :a :b :c) '() '()]
  ;; 122 :op-roll
  ['(1 :a :b :c) '() '()] [:op-roll] ['(:b :a :c) '() '()]
  ;; 124 :op-swap
  ['(:a :b :c) '() '()] [:op-swap] ['(:b :a :c) '() '()]
  ;; 123 :op-rot
  ['(:a :b :c) '() '()] [:op-rot] ['(:b :c :a) '() '()]
  ;; 125 :op-tuck
  ['(:a :b :c) '() '()] [:op-tuck] ['(:a :b :a :c) '() '()]

  ;; 135 :op-equal
  ['(:a :a) '() '()] [:op-equal] ['(1) '() '()]
  ['(:a :b) '() '()] [:op-equal] ['(0) '() '()]
  ;; 136 :op-equalverify
  ['(:a :a) '() '()] [:op-equalverify] ['() '() '()]
  ['(:a :b) '() '()] [:op-equalverify] [:invalid '() '()]

  ;; 169 :op-hash160
  [(list (hex->bytes "aabbcc")) '() '()]
  [:op-hash160]
  [(list "0bfbcadae145d870428db173412d2d860b9acf5e") '() '()]

  ;; TODO: Move compound tests to its own test block.
  ;; ===== Conventional txn =====

  ;:op-dup :op-hash160 "pubkeyhash" :op-equalverify :op-checksig

  [(list (hex->bytes "aabbcc")
         (hex->bytes "001122")) '() '()]
  [:op-dup :op-hash160]
  [(list "0bfbcadae145d870428db173412d2d860b9acf5e"
         "aabbcc"
         "001122")
   '() '()]

  ;; Pre-state
  [(list (hex->bytes "aabbcc")
         (hex->bytes "001122")) '() '()]
  ;; Script
  [:op-dup
   :op-hash160
   "0bfbcadae145d870428db173412d2d860b9acf5e" ]
  ;; Post-state
  [(list "0bfbcadae145d870428db173412d2d860b9acf5e"
         "0bfbcadae145d870428db173412d2d860b9acf5e"
         "aabbcc"
         "001122") '() '()]

  ;; Pre-state
  [(list (hex->bytes "aabbcc")
         (hex->bytes "001122")) '() '()]
  ;; Script
  [:op-dup
   :op-hash160
   (hex->bytes "0bfbcadae145d870428db173412d2d860b9acf5e")
   :op-equalverify]
  ;; Post-state
  [(list "aabbcc"
         "001122") '() '()]

  )

  ['() '() '()]

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Below I'm experimenting with some op-checksig
;; implementation ideas.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; (let [world {:txIn {:txIn/idx 0
;;                     :txIn/prevTxOut {:txOut/script "lol"}}}]
;;   (let [pubkey (hex->bytes "abcd11")
;;         sig (hex->bytes
;;              "0bfbcadae145d870428db173412d2d860b9acf5e")]
;;     (execute [:op-checksig] [(list pubkey sig) '() '()] world)))

(defn assoc-subscript2 [txn txin-idx subscript]
  (let [all-txins (:txn/txIns txn)
        target-txin (first
                     (filter #(= txin-idx (:txIn/idx %))
                             all-txins))
        other-txins (remove #(= txin-idx (:txIn/idx %)) all-txins)]
    (let [updated-txin (assoc target-txin :txIn/script subscript)
          updated-txins (conj other-txins updated-txin)]
      (assoc txn :txn/txIns updated-txins))))

;; TODO: This is where I need a robust, recursive touch-all
;; function.
;; (def subscript-bytes
;; (let [txn (db/txn170-1)
;;           ;(db/parent-txn (db/toy-txin))
;;       ]
;;   ;(into {} (:txn/txIns (into {} txn)))
;;   (as-> txn _
;;         (clear-txin-scripts _)
;;         ;(assoc-subscript2 _ 0 (byte-array 32))
;;         (assoc-subscript _ 0 (byte-array 32))
;;         ;(map (fn [[k v]] (class v)) _)
;;         ;(db/touch-all _)
;;         (update-in _ [:txn/txOuts]
;;                    (partial map (comp (partial into {}) d/touch)))
;;         (update-in _ [:txn/txIns]
;;                    (fn [txins]
;;                      (map #(update-in % [:txIn/prevTxOut]
;;                                  (partial d/touch))
;;                           txins)))

;;         ;; Couldn't get this to work
;;         ;;   {:txncopy _
;;         ;;    :hashtype 1}
;;         ;;   (hyzhenhok.codec/encode txncopy-codec _)
;;         ;; So I'll just add the hashtype bytes myself.

;;         (codec2/encode-txn _)
;;         ;--(hyzhenhok.codec/decode hyzhenhok.codec/TxnCodec _)
;;         )))

;; (-> (codec2/decode-txn subscript-bytes)
;;     (hexifying-walk))

;; (let [subscript+hashtype (concat-bytes
;;                           (buf->bytes subscript-bytes)
;;                           (into-byte-array [1 0 0 0]))]
;;   (-> (crypto/double-sha256 subscript+hashtype)
;;       bytes->hex)
;;   ;(bytes->hex subscript+hashtype)
;;   )


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


;; (def subscript-bytes
;;   (let [txn (db/txn170-1)
;;           ;(db/parent-txn (db/toy-txin))
;;       ]
;;   ;(into {} (:txn/txIns (into {} txn)))
;;   (as-> txn _
;;         (clear-txin-scripts _)
;;         ;(assoc-subscript2 _ 0 (byte-array 32))
;;         (assoc-subscript _ 0 (byte-array 32))
;;         ;(map (fn [[k v]] (class v)) _)
;;         ;(db/touch-all _)
;;         (update-in _ [:txn/txOuts]
;;                    (partial map (comp (partial into {}) d/touch)))
;;         (update-in _ [:txn/txIns]
;;                    (fn [txins]
;;                      (map #(update-in % [:txIn/prevTxOut]
;;                                  (partial d/touch))
;;                           txins)))

;;         ;; Couldn't get this to work
;;         ;;   {:txncopy _
;;         ;;    :hashtype 1}
;;         ;;   (hyzhenhok.codec/encode txncopy-codec _)
;;         ;; So I'll just add the hashtype bytes myself.

;;         (codec2/encode-txn _)
;;         ;--(hyzhenhok.codec/decode hyzhenhok.codec/TxnCodec _)
;;         )))

;; (-> (codec2/decode-txn subscript-bytes)
;;     (hexifying-walk))

;; (let [subscript+hashtype (concat-bytes
;;                           (buf->bytes subscript-bytes)
;;                           (into-byte-array [1 0 0 0]))]
;;   (-> (crypto/double-sha256 subscript+hashtype)
;;       bytes->hex)
;;   ;(bytes->hex subscript+hashtype)
;;   )

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; (defn test-txn [] (-> "6f7cf9580f1c2dfb3c4d5d043cdbb128c640e3f20161245aa7372e9666168516" db/find-txn-by-hash))

;; (defn test-txin [] (->> (test-txn) :txn/txIns (filter #(= 1 (:txIn/idx %))) first))

;; ;;

;; (codec2/touch-all (test-txin))

;; (extract-hash-type (:txIn/script (test-txin)))

;; (let [txn (first (:txn/_txIns (test-txin)))
;;       txin-idx (:txIn/idx (test-txin))
;;       hashtype (extract-hash-type (:txIn/script (test-txin)))
;;       sig
;;       subscript (-> (:txIn/prevTxOut txin)
;;                     (:txOut/script))])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;



;; (def b37503 (d/touch (db/find-blk-by-hash2 "000000008cedea309a62acd9e08427b3cc4e2d03366028832c27bad81c6d96e3")))

;; (def b37513 (d/touch (db/find-blk-by-hash2 "00000000a80b44e97fa7973384bbb5b94aad4fbc144e59ceb66bf053dfdec8ba")))

;; (def tin (d/touch (first (:txn/txIns (first (filter #(= 1 (:txn/idx %)) (:block/txns b37513)))))))

;; (def tsig+hashcode
;;   (-> (parse (:txIn/script tin))
;;       (execute {})
;;       (ffirst)
;;       (ubytes->bytes)))

;; (def tpubhash
;;   (-> (parse (:txOut/script (:txIn/prevTxOut tin)))
;;       first
;;       (ubytes->bytes)))

;; (def tworld {:txin tin})

;; (def ttxn (first (:txn/_txIns tin)))

;; (def ttxin-idx (:txIn/idx tin))

;; (def tsig (drop-last-bytes 1 tsig+hashcode))

;; (def tsubscript (-> (:txIn/prevTxOut tin)
;;                     (:txOut/script)))

;; (def ttxncopy (-> ttxn
;;                   clear-txin-scripts
;;                   (assoc-subscript ttxin-idx tsubscript)))

;; ;; assoc-subscript

;; (let [all-txins (:txn/txIns ttxn)
;;       target-txin (-> (filter #(= ttxin-idx (:txIn/idx %))
;;                               all-txins)
;;                       first)
;;       other-txins (remove #(= ttxin-idx (:txIn/idx %)) all-txins)
;;       updated-txin (-> (codec2/touch-all target-txin)
;;                        (assoc :txIn/script tsubscript))
;;       updated-txins (conj other-txins updated-txin)]
;;   ;target-txin
;;   ;other-txins
;;   ;(bytes->hex (:txIn/script updated-txin))
;;   updated-txins
;;   (let [txncopy (assoc (codec2/touch-all ttxn)
;;                   :txn/txIns updated-txins)
;;         txncopy-bytes (codec2/encode-txn txncopy)
;;         txncopy+hashtype (concat-bytes
;;                           (buf->bytes txncopy-bytes)
;;                           (into-byte-array [1 0 0 0]))
;;         txncopy-hash (crypto/double-sha256 txncopy+hashtype)
;;         ]
;;     txncopy-bytes
;;     txncopy+hashtype
;;     (bytes->hex txncopy-hash)))

;; (def txncopy-hash256 (hex->bytes "a8a513d60c4d11bc2c18edbd3385aff6ff81e778562343d17b90d68a8d1ddae8"))

;; ;(key/verify data sig pub-hash)

;; (key/verify txncopy-hash256 tsig tpubhash)

;; (let [b37513 (db/find-blk-by-hash2 "00000000a80b44e97fa7973384bbb5b94aad4fbc144e59ceb66bf053dfdec8ba")
;;       txin (-> (filter #(= 1 (:txn/idx %)) (:block/txns b37513))
;;                first
;;                :txn/txIns
;;                first)
;;       txin-state (-> (parse (:txIn/script txin))
;;                      (execute ['() '() '()] {}))
;;       parsed-txout-script (-> (:txIn/prevTxOut txin)
;;                               :txOut/script
;;                               parse)]
;;   ;(execute parsed-txout-script txin-state {:txin tin})
;;   ;txin-state
;;   parsed-txout-script
;;   )

;; (expect ['(1) '() '()]
;;   (let [txin (-> (db/find-txn-by-hash "8697331c3124c8a4cf2f43afb5732374ea13769e42f10aa3a98148a08989af5e")
;;                  :txn/txIns
;;                  first)
;;         txin-sig (hex->bytes "3045022100eaa5542714d1e31eada58c31e6ac77774ae803a696884b77a24936ae0da8dd0702207b34d1b20784412f625821d97eae2ccc27376521b1c6bfb8b37fdde9e697a54301")
;;         txout-pubkey (hex->bytes "04dcf9e313efd7aec54c423d25a559a83311ec3574f5b5600f43f8afbb89791bf7ae4cf7c3b920894b350e07ee5a1d384965e7a6a6742cbc793800d33d6a4562bd")]
;;     (execute
;;      [txout-pubkey :op-checksig]
;;      [(list txin-sig) '() '()]
;;      {:txin txin})))
