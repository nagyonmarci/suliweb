// pstFinderController.js

angular.module('myApp').controller('PstFinderController', ['$scope', '$http', function($scope, $http) {
  $scope.searchAndWritePstToTxt = function(directories, excludedDirectories, outputFile) {
    var params = {
      directories: directories,
      excludedDirectories: excludedDirectories,
      outputFile: outputFile
    };

    $http.get('/find/pstToTxt', { params: params })
      .then(function(response) {
        alert('Files processed successfully: ' + response.data);
      }, function(error) {
        alert('Error processing files: ' + error.data);
      });
  };

  $scope.searchAndWritePst = function(directories, excludedDirectories) {
    var params = {
      directories: directories,
      excludedDirectories: excludedDirectories
    };

    $http.get('/find/pst', { params: params })
      .then(function(response) {
        alert('Files processed successfully: ' + response.data);
      }, function(error) {
        alert('Error processing files: ' + error.data);
      });
  };

  $scope.updateDatabaseFileRecords = function() {
    $http.get('/find/updateDb')
      .then(function(response) {
        alert('Database records updated successfully: ' + response.data);
      }, function(error) {
        alert('Error updating database records: ' + error.data);
      });
  };
}]);
