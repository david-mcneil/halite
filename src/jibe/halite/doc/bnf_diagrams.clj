;; Copyright (c) 2022 Viasat, Inc.
;; Licensed under the MIT license

(ns jibe.halite.doc.bnf-diagrams
  (:require [clojure.string :as string]
            [jibe.halite.doc.utils :as utils])
  (:import [net.nextencia.rrdiagram.grammar.model GrammarToRRDiagram BNFToGrammar]
           [net.nextencia.rrdiagram.grammar.rrdiagram RRDiagramToSVG]))

(defn adjust-connector-style
  "RRDiagramToSVG provides an interface for setting on connector path strokes
  only their color, nothing else. So to adjust width, we have to apply unstable
  private knowledge."
  [svg-str]
  (let [desired-style "fill: none; stroke: #888888; stroke-width: 2px;"]
    (-> svg-str
        (string/replace #"(<style.*?>.*[.]c[{]).*?([}].*</style>)"
                        (str "$1" desired-style "$2"))
        (string/replace #"(d=\"M0 )(\d+)"
                        (fn [[_ prefix y-offset-str]]
                          (str prefix (+ -4 (Long/parseLong y-offset-str))))))))

(defn produce-diagram [out-file-name ^String rule-str]
  (let [gtrd (GrammarToRRDiagram.)
        rts (RRDiagramToSVG.)
        rule-svg (->> rule-str
                      (.convert (BNFToGrammar.))
                      .getRules
                      (into [])
                      (map #(.convert gtrd %))
                      (map #(.convert rts %))
                      first
                      adjust-connector-style)]
    (utils/spit-dir out-file-name rule-svg)))

(defn- rule-from-partitioned-bnf [partitioned-bnf k-f]
  (str "RULE = "
       "("
       (->> partitioned-bnf
            (map (fn [[n m]]
                   (let [bnf (k-f m)]
                     (when bnf
                       (str "("
                            "'" n ":' " "(" bnf ")"
                            ")")))))
            (remove nil?)
            (string/join " |\n"))
       ")"
       ";"))

(defn produce-basic-bnf-diagrams [all-file-name all-file-name-j basic-bnf]
  (produce-diagram (str "doc/halite-bnf-diagrams/basic-syntax/" all-file-name)
                   (rule-from-partitioned-bnf (partition 2 basic-bnf) :bnf))
  (produce-diagram (str "doc/halite-bnf-diagrams/basic-syntax/" all-file-name-j)
                   (rule-from-partitioned-bnf (partition 2 basic-bnf) (fn [bnf-map] (get bnf-map :bnf-j (:bnf bnf-map)))))

  (->> (partition 2 basic-bnf)
       (map (fn [[n {:keys [bnf]}]]
              (when bnf
                (produce-diagram (str "doc/halite-bnf-diagrams/basic-syntax/" n ".svg")
                                 (str "RULE = " "(" bnf ")" ";")))))
       dorun)

  (->> (partition 2 basic-bnf)
       (map (fn [[n bnf-map]]
              (let [bnf-j (get bnf-map :bnf-j (:bnf bnf-map))]
                (when bnf-j
                  (produce-diagram (str "doc/halite-bnf-diagrams/basic-syntax/" (utils/translate-op-name-to-jadeite n) "-j" ".svg")
                                   (str "RULE = " "(" bnf-j ")" ";"))))))
       dorun))

#_(defn produce-basic-bnf-diagrams-for-tag [basic-bnf tag]
    (let [filtered-partitioned-bnf (->> (partition 2 basic-bnf)
                                        (filter (fn [[k v]]
                                                  (let [{:keys [tags]} v]
                                                    (when tags
                                                      (tags tag))))))]
      (produce-diagram (str "doc/halite-bnf-diagrams/basic-syntax/" (name tag) ".svg")
                       (rule-from-partitioned-bnf filtered-partitioned-bnf :bnf))
      (produce-diagram (str "doc/halite-bnf-diagrams/basic-syntax/" (str (name tag) "-j" ".svg"))
                       (rule-from-partitioned-bnf filtered-partitioned-bnf (fn [bnf-map] (get bnf-map :bnf-j (:bnf bnf-map)))))))

(defn produce-bnf-diagrams [op-maps op-maps-j all-filename all-filename-j]
  (let [op-keys (keys op-maps)
        rules-strs (->> op-maps
                        (mapcat (fn [[op {:keys [sigs]}]]
                                  (->> sigs
                                       (map (fn [i [args-bnf result-bnf]]
                                              {:op-name op
                                               :sig-index i
                                               :rule-str (str (string/replace op "=" "equal") i " = "
                                                              (when-not (string/starts-with? op "$") "'('")
                                                              "'" op "' " args-bnf
                                                              (when-not (string/starts-with? op "$") "')'")
                                                              " '»' " result-bnf ";")})
                                            (range))))))
        rules-strs-j (->> op-maps-j
                          (mapcat (fn [[op {:keys [sigs-j]}]]
                                    (->> sigs-j
                                         (map (fn [i [args-bnf result-bnf]]
                                                {:op-name op
                                                 :sig-index i
                                                 :rule-str (str (string/replace op "=" "equal") i " = "
                                                                (str "( " args-bnf
                                                                     " '»' " result-bnf " )"))})
                                              (range))))))
        single-rules-strs (->> op-maps
                               (map (fn [[op {:keys [sigs]}]]
                                      (str "("
                                           (when-not (string/starts-with? op "$") "'('")
                                           "'" op "' "
                                           " ( "
                                           (->> sigs
                                                (map (fn [[args-bnf result-bnf]]
                                                       (str "( " args-bnf
                                                            (when-not (string/starts-with? op "$") "')'")
                                                            " '»' " result-bnf " )")))
                                                (string/join " |\n"))
                                           " ) "

                                           ")"))))
        single-rules-strs-j (->> op-maps-j
                                 (map (fn [[op {:keys [sigs-j]}]]
                                        [op (str " ( "
                                                 (->> sigs-j
                                                      (map (fn [[args-bnf result-bnf]]
                                                             (str "( " args-bnf
                                                                  " '»' " result-bnf " )")))
                                                      (string/join " |\n"))
                                                 " ) ")]))
                                 (remove nil?))]
    (let [rule-str (str "RULE = "
                        "("
                        (->> single-rules-strs
                             sort
                             (string/join " |\n"))
                        ")"
                        ";")]
      (produce-diagram (str "doc/halite-bnf-diagrams/" all-filename) rule-str))
    (let [rule-str (str "RULE = "
                        "("
                        (->> single-rules-strs-j
                             (sort-by first)
                             (map second)
                             (string/join " |\n"))
                        ")"
                        ";")]
      (produce-diagram (str "doc/halite-bnf-diagrams/" all-filename-j) rule-str))
    (->> rules-strs
         (map (fn [{:keys [op-name sig-index ^String rule-str]}]
                (produce-diagram (str "doc/halite-bnf-diagrams/op/" (str (utils/safe-op-name op-name) "-" sig-index ".svg")) rule-str)))
         dorun)
    (->> rules-strs-j
         (map (fn [{:keys [op-name sig-index ^String rule-str]}]
                (produce-diagram (str "doc/halite-bnf-diagrams/op/" (str (utils/safe-op-name op-name) "-" sig-index "-j" ".svg")) rule-str)))
         dorun)))