<?php
  /**
   * Created by PhpStorm.
   * User: msmith
   * Date: 2/8/14
   * Time: 10:48 PM
   */

  function startsWith($haystack, $needle)
  {
    return $needle === "" || strpos($haystack, $needle) === 0;
  }
  function endsWith($haystack, $needle)
  {
    return $needle === "" || substr($haystack, -strlen($needle)) === $needle;
  }

  function generateAudioTag($file) {
    return '<audio controls>'.
        '<source src="'.$file.'" type="audio/mpeg">'.
      '</audio>';
  }

  function list_contents($file) {
    if ($handle = opendir($file)) {
      /* This is the correct way to loop over the directory. */
      while (false !== ($new_file = readdir($handle))) {
        if ($new_file !== '.' && $new_file !== '..') {
          $new_file = $file . DIRECTORY_SEPARATOR . $new_file;
          if (is_dir($new_file)) {
            echo "<li>$new_file</li>";
            echo '<ul>';
            list_contents($new_file);
            echo '</ul>';
          } elseif (is_file($new_file)) {
            if (endsWith($new_file, '.mp3')) {
              $new_file = generateAudioTag($new_file);
            }
            echo '<li>',$new_file,'</li>';
          } else {
            echo "<li>? $new_file</li>";
          }
        }
      }

      closedir($handle);
    }
  }

  $dir = 'Music';
  echo "<ul>";
  list_contents($dir);
  echo "</ul>";
