<?php
  /**
   * Created by PhpStorm.
   * User: msmith
   * Date: 2/8/14
   * Time: 9:11 PM
   */

  if ($handle = opendir('Music')) {
    echo "Directory handle: $handle<br />";
    echo "Entries:<br /><br />";

    /* This is the correct way to loop over the directory. */
    while (false !== ($entry = readdir($handle))) {
      if (is_dir($entry)) {
        echo "Directory: $entry<br />";
      }
      elseif (is_file($entry)) {
        echo "File: $entry<br />";
      }
      else {
        echo "? $entry<br />";
      }
    }

    closedir($handle);
  }