(ns ttt.cli.ui)

(def ansi-codes
  {:reset "\u001b[0m"
   :bold "\u001b[1m"
   :dim "\u001b[2m"
   :red "\u001b[31m"
   :green "\u001b[32m"
   :yellow "\u001b[33m"
   :blue "\u001b[34m"
   :cyan "\u001b[36m"})

(defn color-enabled?
  []
  (and (some? (System/console))
       (not= "dumb" (System/getenv "TERM"))
       (nil? (System/getenv "NO_COLOR"))))

(defn style
  [text & styles]
  (if (color-enabled?)
    (str (apply str (map ansi-codes styles))
         text
         (:reset ansi-codes))
    text))

(defn label
  [text]
  (style text :cyan :bold))

(defn headline
  [text]
  (style text :blue :bold))

(defn success
  [text]
  (style text :green :bold))

(defn warning
  [text]
  (style text :yellow :bold))

(defn error
  [text]
  (style text :red :bold))

(defn muted
  [text]
  (style text :dim))

(defn accent
  [text]
  (style text :blue :bold))

(defn strong
  [text]
  (style text :bold))

(defn progress
  [text]
  (style text :yellow :bold))
