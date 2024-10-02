(defproject format-errors "v1"
  :description "A Clojure module for producing formatted errors."
  :license {:name "MIT"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/tools.logging "1.2.4"]
                 [prismatic/schema "1.4.0"]]
  :profiles {:test [:test-dep]
             :dev [:test-dep]})
